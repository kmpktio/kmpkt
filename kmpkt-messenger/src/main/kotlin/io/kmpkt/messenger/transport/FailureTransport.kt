package io.kmpkt.messenger.transport

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.stamp.ErrorDetailsStamp
import io.kmpkt.messenger.stamp.SentToFailureTransportStamp

/**
 * Sends [envelope] from [originTransportName] to this failure transport unless it was already
 * put aside once, returning the resulting envelope or `null` when skipped.
 */
internal suspend fun Transport.putAside(
    originTransportName: String,
    envelope: Envelope,
    failure: Exception,
): Envelope? {
    if (envelope.last<SentToFailureTransportStamp>() != null) return null

    return send(envelope.with(SentToFailureTransportStamp(originTransportName), ErrorDetailsStamp.from(failure)))
}
