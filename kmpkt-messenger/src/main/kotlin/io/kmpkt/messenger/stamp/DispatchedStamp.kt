package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Stamp
import java.time.Instant

/** When a message was first dispatched onto the bus. */
public data class DispatchedStamp(
    public val dispatchedAt: Instant,
) : Stamp
