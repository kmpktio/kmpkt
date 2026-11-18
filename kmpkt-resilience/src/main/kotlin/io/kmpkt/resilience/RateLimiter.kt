package io.kmpkt.resilience

/**
 * Port for limiting the rate of operations by acquiring permits.
 */
public interface RateLimiter {
    public suspend fun tryAcquire(permits: Int = 1): Boolean

    public suspend fun acquire(permits: Int = 1)
}
