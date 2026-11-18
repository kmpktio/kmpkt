package io.kmpkt.messenger.transport

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.stamp.DelayStamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import kotlin.time.toJavaDuration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A queue that lives and dies with the process. For tests, and for what may be lost on a restart - a
 * failure transport that somebody looks at while the application is up, say.
 *
 * [receive] hands out one envelope at a time, oldest first, skipping what a [DelayStamp] holds back. An
 * envelope is gone from the queue once it was handed out: [ack] and [reject] only record what became of it,
 * and a rejected envelope comes back only if whoever rejected it sent it again.
 */
public class InMemoryTransport(
    private val clock: Clock = Clock.systemUTC(),
) : ListableTransport {
    private class Queued(
        val id: String,
        val envelope: Envelope,
        val availableAt: Instant,
    )

    private val mutex = Mutex()
    private val queue = mutableListOf<Queued>()
    private val acknowledgedEnvelopes = mutableListOf<Envelope>()
    private val rejectedEnvelopes = mutableListOf<Envelope>()

    /** What is waiting, delayed envelopes included, oldest first. */
    public suspend fun pending(): List<Envelope> = mutex.withLock { queue.map { it.envelope } }

    public suspend fun acknowledged(): List<Envelope> = mutex.withLock { acknowledgedEnvelopes.toList() }

    public suspend fun rejected(): List<Envelope> = mutex.withLock { rejectedEnvelopes.toList() }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun send(envelope: Envelope): Envelope {
        val delay = envelope.last<DelayStamp>()?.delay
        val availableAt = if (delay == null) clock.instant() else clock.instant().plus(delay.toJavaDuration())
        mutex.withLock { queue += Queued(Uuid.random().toString(), envelope, availableAt) }

        return envelope
    }

    override fun all(): Flow<QueuedEnvelope> =
        flow {
            val snapshot = mutex.withLock { queue.map { QueuedEnvelope(it.id, it.envelope) } }
            emitAll(snapshot.asFlow())
        }

    override suspend fun find(id: String): QueuedEnvelope? =
        mutex.withLock {
            queue.firstOrNull { it.id == id }?.let { QueuedEnvelope(it.id, it.envelope) }
        }

    override suspend fun remove(id: String): Boolean = mutex.withLock { queue.removeIf { it.id == id } }

    override suspend fun receive(): List<Envelope> =
        mutex.withLock {
            val now = clock.instant()
            val next = queue.firstOrNull { !it.availableAt.isAfter(now) }
            if (next != null) queue.remove(next)

            listOfNotNull(next?.envelope)
        }

    override suspend fun ack(envelope: Envelope) {
        mutex.withLock { acknowledgedEnvelopes += envelope }
    }

    override suspend fun reject(envelope: Envelope) {
        mutex.withLock { rejectedEnvelopes += envelope }
    }
}
