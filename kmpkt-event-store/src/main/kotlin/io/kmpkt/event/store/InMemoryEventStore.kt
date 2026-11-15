package io.kmpkt.event.store

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.exception.ConcurrencyConflict
import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default, non-persistent [EventStore]. Everything is lost on restart — fine for development, testing, or an
 * app that doesn't need durability. Add `kmpkt-persistence-jdbc` (or another store module) for a real one.
 */
public class InMemoryEventStore(
    private val checkpointPredicate: EventCheckpointPredicate = EventCheckpointPredicate.NONE,
) : EventStore {
    private val streams = mutableMapOf<String, MutableList<DomainEvent<*>>>()
    private val log = mutableListOf<StoredEvent>()
    private var nextPosition = 1L
    private val mutex = Mutex()

    override suspend fun <A : AggregateRoot<A>> append(
        type: AggregateType<A>,
        id: AggregateId<A>,
        expectedVersion: Long,
        events: List<DomainEvent<A>>,
    ) {
        if (events.isEmpty()) return
        mutex.withLock {
            val stream = streams.getOrPut(streamOf(type, id)) { mutableListOf() }
            val currentVersion = stream.lastOrNull()?.version ?: 0L
            if (currentVersion != expectedVersion) throw ConcurrencyConflict(id, expectedVersion)
            stream += events
            events.forEach { log += StoredEvent(nextPosition++, it) }
        }
    }

    override suspend fun <A : AggregateRoot<A>> load(
        type: AggregateType<A>,
        id: AggregateId<A>,
        fromVersion: Long,
        limit: Int?,
    ): List<DomainEvent<A>> =
        mutex.withLock {
            val stream = streams[streamOf(type, id)].orEmpty()
            val lastCheckpointVersion = stream.lastOrNull(checkpointPredicate::isCheckpoint)?.version ?: 0L
            stream
                .filter { it.version > fromVersion && it.version >= lastCheckpointVersion }
                .let { events -> limit?.let(events::take) ?: events }
                .map {
                    // Safe: a stream only ever holds events appended for that very (type, id).
                    @Suppress("UNCHECKED_CAST")
                    it as DomainEvent<A>
                }
        }

    override suspend fun loadFrom(
        after: Long,
        limit: Int,
    ): List<StoredEvent> =
        mutex.withLock {
            log.filter { it.position > after && !checkpointPredicate.isCheckpoint(it.event) }.take(limit)
        }

    override suspend fun latestPosition(): Long =
        mutex.withLock {
            log.lastOrNull { !checkpointPredicate.isCheckpoint(it.event) }?.position ?: 0
        }

    /** A stream's key in [streams]: the aggregate type and id together, since ids are only unique per type. */
    private fun <A : AggregateRoot<A>> streamOf(
        type: AggregateType<A>,
        id: AggregateId<A>,
    ): String = "$type/$id"
}
