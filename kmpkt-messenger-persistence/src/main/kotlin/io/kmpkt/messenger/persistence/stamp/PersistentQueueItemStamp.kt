package io.kmpkt.messenger.persistence.stamp

import io.kmpkt.messenger.message.Stamp

/**
 * Identifies the claimed [io.kmpkt.persistence.QueuedItem] so
 * [io.kmpkt.messenger.persistence.transport.PersistentTransport.ack] and
 * [io.kmpkt.messenger.persistence.transport.PersistentTransport.reject] can settle the exact queue entry.
 */
public data class PersistentQueueItemStamp(
    public val itemId: String,
) : Stamp
