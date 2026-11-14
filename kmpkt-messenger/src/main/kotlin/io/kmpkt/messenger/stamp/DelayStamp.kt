package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Stamp
import kotlin.time.Duration

/**
 * Asks the transport an envelope is sent to not to hand it out again before [delay] has passed. A request:
 * a transport that cannot delay delivers at once.
 */
public data class DelayStamp(
    public val delay: Duration,
) : Stamp
