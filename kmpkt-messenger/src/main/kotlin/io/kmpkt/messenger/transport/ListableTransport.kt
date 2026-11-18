package io.kmpkt.messenger.transport

import io.kmpkt.messenger.message.Envelope
import kotlinx.coroutines.flow.Flow

/**
 * An [Envelope] paired with its transport-specific identifier.
 */
public data class QueuedEnvelope(
    val id: String,
    val envelope: Envelope,
)

/**
 * [Transport] supporting non-destructive inspection and removal of queued envelopes by identifier,
 * used by failure-management commands (`messenger:failed:*`).
 */
public interface ListableTransport : Transport {
    public fun all(): Flow<QueuedEnvelope>

    public suspend fun find(id: String): QueuedEnvelope?

    public suspend fun remove(id: String): Boolean
}
