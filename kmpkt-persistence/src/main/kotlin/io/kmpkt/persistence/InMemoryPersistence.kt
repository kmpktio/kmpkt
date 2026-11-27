package io.kmpkt.persistence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

private class InMemoryTransactionContext(
    val owner: InMemoryPersistence,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InMemoryTransactionContext>
}

/**
 * In-memory [Persistence] backed by coroutine-safe collections. Suitable for tests and single-process runs.
 */
public class InMemoryPersistence : Persistence {
    private val txMutex = Mutex()
    private val kv = mutableMapOf<String, MutableMap<String, String>>()
    private val logs = mutableMapOf<String, MutableList<LogEntry>>()
    private val queueItems = mutableMapOf<String, MutableMap<String, MutableQueueEntry>>()
    private val locks = mutableMapOf<String, Mutex>()
    private val lockGuard = Mutex()

    override fun keyValueStore(name: String): KeyValueStore =
        object : KeyValueStore {
            override suspend fun get(key: String): String? = withTx { kv[name]?.get(key) }

            override suspend fun put(
                key: String,
                value: String,
            ) {
                withTx { kv.getOrPut(name) { mutableMapOf() }[key] = value }
            }

            override suspend fun delete(key: String) {
                withTx { kv[name]?.remove(key) }
            }

            override suspend fun clear() {
                withTx { kv.remove(name) }
            }
        }

    override fun appendLog(name: String): AppendLog =
        object : AppendLog {
            override suspend fun append(
                streamType: String,
                streamId: String,
                expectedVersion: Long,
                entries: List<NewLogEntry>,
            ) {
                if (entries.isEmpty()) return
                withTx {
                    val log = logs.getOrPut(name) { mutableListOf() }
                    val currentVersion =
                        log
                            .filter { it.streamType == streamType && it.streamId == streamId }
                            .maxOfOrNull { it.version }
                            ?: 0L
                    if (currentVersion != expectedVersion) {
                        throw LogVersionConflictException(streamType, streamId, expectedVersion)
                    }
                    var nextVersion = currentVersion
                    entries.forEach { entry ->
                        nextVersion += 1
                        val position = (log.lastOrNull()?.position ?: 0L) + 1L
                        log +=
                            LogEntry(
                                position = position,
                                id = entry.id,
                                streamType = streamType,
                                streamId = streamId,
                                version = nextVersion,
                                type = entry.type,
                                payload = entry.payload,
                                occurredAt = entry.occurredAt,
                                checkpoint = entry.checkpoint,
                            )
                    }
                }
            }

            override suspend fun readStream(
                streamType: String,
                streamId: String,
                fromVersion: Long,
                limit: Int?,
                fromLatestCheckpoint: Boolean,
            ): List<LogEntry> =
                withTx {
                    val log = logs[name].orEmpty()
                    val stream = log.filter { it.streamType == streamType && it.streamId == streamId }
                    val checkpointVersion =
                        if (fromLatestCheckpoint) {
                            stream.lastOrNull { it.checkpoint }?.version ?: 0L
                        } else {
                            0L
                        }
                    val effectiveFrom = maxOf(fromVersion, checkpointVersion - 1L)
                    val filtered = stream.filter { it.version > effectiveFrom }
                    if (limit == null) filtered else filtered.take(limit)
                }

            override suspend fun readAfter(
                afterPosition: Long,
                limit: Int,
                includeCheckpoints: Boolean,
            ): List<LogEntry> =
                withTx {
                    logs[name]
                        .orEmpty()
                        .asSequence()
                        .filter { it.position > afterPosition && (includeCheckpoints || !it.checkpoint) }
                        .take(limit)
                        .toList()
                }

            override suspend fun latestPosition(includeCheckpoints: Boolean): Long =
                withTx {
                    logs[name].orEmpty().lastOrNull { includeCheckpoints || !it.checkpoint }?.position ?: 0L
                }
        }

    override fun workQueue(name: String): WorkQueue =
        object : WorkQueue {
            override suspend fun enqueue(
                id: String,
                payload: String,
                availableAt: Instant,
            ) {
                withTx {
                    queueItems.getOrPut(name) { mutableMapOf() }[id] =
                        MutableQueueEntry(
                            id = id,
                            payload = payload,
                            availableAt = availableAt,
                            attempts = 0,
                            status = QueueStatus.Pending,
                        )
                }
            }

            override suspend fun claim(
                now: Instant,
                limit: Int,
            ): List<QueuedItem> =
                withTx {
                    val items = queueItems[name] ?: return@withTx emptyList()
                    val ready =
                        items.values
                            .asSequence()
                            .filter { it.status == QueueStatus.Pending && !it.availableAt.isAfter(now) }
                            .sortedBy { it.availableAt }
                            .take(limit)
                            .toList()
                    ready.forEach {
                        it.status = QueueStatus.Claimed
                        it.attempts += 1
                    }
                    ready.map { QueuedItem(it.id, it.payload, it.availableAt, it.attempts) }
                }

            override suspend fun ack(id: String) {
                withTx { queueItems[name]?.remove(id) }
            }

            override suspend fun nack(
                id: String,
                availableAt: Instant,
            ) {
                withTx {
                    val entry = queueItems[name]?.get(id) ?: return@withTx
                    entry.status = QueueStatus.Pending
                    entry.availableAt = availableAt
                }
            }

            override suspend fun fail(id: String) {
                withTx { queueItems[name]?.get(id)?.status = QueueStatus.Failed }
            }

            override fun list(): Flow<QueuedItem> =
                flow {
                    val items =
                        withTx {
                            queueItems[name]
                                ?.values
                                ?.asSequence()
                                ?.filter { it.status == QueueStatus.Pending }
                                ?.sortedWith(compareBy<MutableQueueEntry> { it.availableAt }.thenBy { it.id })
                                ?.map { QueuedItem(it.id, it.payload, it.availableAt, it.attempts) }
                                ?.toList()
                                .orEmpty()
                        }
                    items.forEach { emit(it) }
                }

            override suspend fun find(id: String): QueuedItem? =
                withTx {
                    queueItems[name]
                        ?.get(id)
                        ?.takeIf { it.status == QueueStatus.Pending }
                        ?.let { QueuedItem(it.id, it.payload, it.availableAt, it.attempts) }
                }

            override suspend fun remove(id: String): Boolean = withTx { queueItems[name]?.remove(id) != null }
        }

    override suspend fun <R> inTransaction(block: suspend () -> R): R {
        if (coroutineContext[InMemoryTransactionContext]?.owner === this) {
            return block()
        }
        return txMutex.withLock {
            val kvSnapshot = kv.mapValues { (_, entries) -> entries.toMutableMap() }.toMutableMap()
            val logsSnapshot = logs.mapValues { (_, entries) -> entries.toMutableList() }.toMutableMap()
            val queueSnapshot =
                queueItems.mapValues { (_, items) -> items.mapValues { (_, entry) -> entry.copy() }.toMutableMap() }.toMutableMap()
            try {
                withContext(InMemoryTransactionContext(this)) { block() }
            } catch (failure: Throwable) {
                kv.clear()
                kv.putAll(kvSnapshot)
                logs.clear()
                logs.putAll(logsSnapshot)
                queueItems.clear()
                queueItems.putAll(queueSnapshot)
                throw failure
            }
        }
    }

    override suspend fun <R> underLock(
        name: String,
        block: suspend () -> R,
    ): R? {
        val lock = lockGuard.withLock { locks.getOrPut(name) { Mutex() } }
        if (!lock.tryLock()) return null
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private suspend fun <R> withTx(block: () -> R): R =
        if (coroutineContext[InMemoryTransactionContext]?.owner === this) {
            block()
        } else {
            txMutex.withLock { block() }
        }

    private enum class QueueStatus { Pending, Claimed, Failed }

    private data class MutableQueueEntry(
        val id: String,
        val payload: String,
        var availableAt: Instant,
        var attempts: Int,
        var status: QueueStatus,
    )
}
