package io.kmpkt.messenger.bus.middleware

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import io.kmpkt.messenger.stamp.RateLimitedStamp
import io.kmpkt.resilience.RateLimiter

/**
 * Throttles dispatches carrying a [RateLimitedStamp] using the matching [RateLimiter].
 */
public class RateLimitedMiddleware(
    private val limiters: Map<String, RateLimiter>,
) : Middleware {
    public constructor(
        name: String,
        limiter: RateLimiter,
    ) : this(mapOf(name to limiter))

    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        val stamp = envelope.last<RateLimitedStamp>()
        if (stamp != null) {
            val limiter =
                limiters[stamp.limiter]
                    ?: error("Unknown rate limiter '${stamp.limiter}'. Configured: ${limiters.keys.sorted()}")
            limiter.acquire()
        }
        return next(envelope)
    }
}
