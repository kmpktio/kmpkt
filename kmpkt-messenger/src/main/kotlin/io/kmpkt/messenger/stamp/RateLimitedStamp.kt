package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Stamp

/**
 * Requests rate limiting under the named [limiter] via
 * [io.kmpkt.messenger.bus.middleware.RateLimitedMiddleware].
 */
public data class RateLimitedStamp(
    public val limiter: String,
) : Stamp
