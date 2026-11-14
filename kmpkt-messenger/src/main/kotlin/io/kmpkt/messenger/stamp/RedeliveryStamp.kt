package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Stamp

/** How many times this message has been tried again after failing. Absent on a first attempt. */
public data class RedeliveryStamp(
    public val retryCount: Int,
) : Stamp {
    public companion object {
        public fun retryCountOf(envelope: Envelope): Int = envelope.last<RedeliveryStamp>()?.retryCount ?: 0
    }
}
