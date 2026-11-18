package io.kmpkt.resilience

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * Coroutine-safe [RateLimiter] implementing the token-bucket algorithm.
 */
public class TokenBucket(
    private val capacity: Int,
    private val refillTokens: Int,
    private val refillPeriod: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : RateLimiter {
    private val mutex = Mutex()
    private var availableTokens: Double = capacity.toDouble()
    private var lastRefill: Instant = clock.instant()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(refillTokens > 0) { "refillTokens must be positive" }
        require(refillPeriod > Duration.ZERO) { "refillPeriod must be positive" }
    }

    override suspend fun tryAcquire(permits: Int): Boolean {
        require(permits in 1..capacity) { "permits ($permits) must be in 1..$capacity" }
        return mutex.withLock {
            refill()
            if (availableTokens >= permits) {
                availableTokens -= permits
                true
            } else {
                false
            }
        }
    }

    override suspend fun acquire(permits: Int) {
        require(permits in 1..capacity) { "permits ($permits) must be in 1..$capacity" }
        while (true) {
            val waitDuration =
                mutex.withLock {
                    refill()
                    if (availableTokens >= permits) {
                        availableTokens -= permits
                        return
                    }
                    val missing = permits - availableTokens
                    val millisPerToken = refillPeriod.toDouble(DurationUnit.MILLISECONDS) / refillTokens
                    (missing * millisPerToken).toLong().coerceAtLeast(1L).milliseconds
                }
            delay(waitDuration)
        }
    }

    private fun refill() {
        val now = clock.instant()
        val elapsedMillis =
            java.time.Duration
                .between(lastRefill, now)
                .toMillis()
        if (elapsedMillis <= 0L) return
        val added = elapsedMillis.toDouble() * refillTokens / refillPeriod.toDouble(DurationUnit.MILLISECONDS)
        availableTokens = minOf(capacity.toDouble(), availableTokens + added)
        lastRefill = now
    }
}
