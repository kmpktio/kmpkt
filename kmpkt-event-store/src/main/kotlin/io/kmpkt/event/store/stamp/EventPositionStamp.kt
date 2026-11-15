package io.kmpkt.event.store.stamp

import io.kmpkt.messenger.message.Stamp

/**
 * Which event of the log an envelope came from, as [io.kmpkt.event.store.StoredEvent.position] gave
 * it.
 *
 * A transport that reads the event store puts this on every envelope it emits, because by the time the
 * envelope comes back to `ack` or `reject` the message itself no longer says where in the log it was - and
 * that position is the whole of what such a transport records.
 */
public data class EventPositionStamp(
    val position: Long,
) : Stamp
