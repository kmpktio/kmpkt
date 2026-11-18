package io.kmpkt.messenger.persistence.transport

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.persistence.serialization.EnvelopeSerializer
import io.kmpkt.messenger.persistence.stamp.PersistentQueueItemStamp
import io.kmpkt.messenger.stamp.DelayStamp
import io.kmpkt.messenger.transport.SetupableTransport
import io.kmpkt.persistence.Persistence
import io.kmpkt.persistence.WorkQueue
import java.time.Clock
import java.util.UUID
import kotlin.time.toJavaDuration

/**
 * Durable [SetupableTransport] backed by a [WorkQueue].
 */
public class PersistentTransport(
    private val workQueue: WorkQueue,
    private val serializer: EnvelopeSerializer,
    private val clock: Clock = Clock.systemUTC(),
) : SetupableTransport {
    public constructor(
        persistence: Persistence,
        queueName: String,
        serializer: EnvelopeSerializer,
        clock: Clock = Clock.systemUTC(),
    ) : this(persistence.workQueue(queueName), serializer, clock)

    override suspend fun setup() {
        workQueue.setup()
    }

    override suspend fun send(envelope: Envelope): Envelope {
        val delay = envelope.last<DelayStamp>()?.delay
        val availableAt = if (delay == null) clock.instant() else clock.instant().plus(delay.toJavaDuration())
        workQueue.enqueue(
            id = UUID.randomUUID().toString(),
            payload = serializer.serialize(envelope),
            availableAt = availableAt,
        )
        return envelope
    }

    override suspend fun receive(): List<Envelope> =
        workQueue.claim(now = clock.instant(), limit = 1).map { item ->
            serializer.deserialize(item.payload).with(PersistentQueueItemStamp(item.id))
        }

    override suspend fun ack(envelope: Envelope) {
        workQueue.ack(envelope.itemIdOrFail())
    }

    override suspend fun reject(envelope: Envelope) {
        workQueue.fail(envelope.itemIdOrFail())
    }

    private fun Envelope.itemIdOrFail(): String =
        last<PersistentQueueItemStamp>()?.itemId
            ?: error("An envelope returned to PersistentTransport without its PersistentQueueItemStamp")
}
