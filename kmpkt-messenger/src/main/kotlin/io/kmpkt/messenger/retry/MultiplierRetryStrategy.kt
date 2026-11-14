package io.kmpkt.messenger.retry

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.stamp.RedeliveryStamp
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Retries up to [maxRetries] times, waiting [delay] before the first and [multiplier] times as long before
 * each one after, never longer than [maxDelay] - `Duration.ZERO` meaning no limit.
 *
 * [jitter] spreads the wait by up to that fraction of it either way, so that messages which failed together
 * do not all come back together.
 *
 * It asks nothing about the failure: whatever is not marked [UnrecoverableException] is retried, which is
 * decided before a strategy is asked at all. An application that wants to retry less - only a lost
 * optimistic lock, say - writes a [RetryStrategy] of its own.
 */
public class MultiplierRetryStrategy(
    private val maxRetries: Int = 3,
    private val delay: Duration = 1.seconds,
    private val multiplier: Double = 2.0,
    private val maxDelay: Duration = 10.seconds,
    private val jitter: Double = 0.1,
    private val random: Random = Random.Default,
) : RetryStrategy {
    init {
        require(maxRetries >= 0) { "A retry count cannot be negative, got $maxRetries" }
        require(!delay.isNegative()) { "A delay cannot be negative, got $delay" }
        require(multiplier >= 1) { "A multiplier below 1 would shorten the wait with every retry, got $multiplier" }
        require(!maxDelay.isNegative()) { "A maximum delay cannot be negative, got $maxDelay" }
        require(jitter in 0.0..1.0) { "Jitter is a fraction of the delay between 0 and 1, got $jitter" }
    }

    override fun isRetryable(
        envelope: Envelope,
        failure: Throwable,
    ): Boolean = RedeliveryStamp.retryCountOf(envelope) < maxRetries

    override fun waitingTime(
        envelope: Envelope,
        failure: Throwable,
    ): Duration {
        val waiting = delay * multiplier.pow(RedeliveryStamp.retryCountOf(envelope))
        // Anywhere between `-jitter` and `+jitter` of the wait.
        val spread = if (jitter > 0) waiting * (jitter * (random.nextDouble() * 2 - 1)) else Duration.ZERO
        val jittered = waiting + spread

        return if (maxDelay > Duration.ZERO && jittered > maxDelay) maxDelay else jittered
    }
}
