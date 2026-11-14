package io.kmpkt.worker

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a [Worker] does after a pass of [Work] has thrown.
 *
 * Giving up is a legitimate answer, and often the right one: a subscription stuck on an event it cannot handle
 * will not un-stick itself, and a worker that retries it forever turns a loud failure into an endless stream
 * of log lines nobody reads.
 */
public fun interface RetryStrategy {
    /**
     * How long to wait before attempt [attempt] - `1` being the first retry after the original failure - or
     * `null` to stop retrying and let the worker fail.
     */
    public fun delayBefore(attempt: Int): Duration?

    public companion object {
        /** Fail on the first error. */
        public fun none(): RetryStrategy = RetryStrategy { null }

        /** Up to [times] retries, all after the same [delay]. */
        public fun fixed(
            times: Int,
            delay: Duration = 1.seconds,
        ): RetryStrategy = RetryStrategy { attempt -> delay.takeIf { attempt <= times } }

        /**
         * Up to [times] retries, waiting [initial] and multiplying by [factor] each time, never longer than
         * [max]. The usual choice for something that may be failing because of a transient outside problem.
         */
        public fun backOff(
            times: Int = 5,
            initial: Duration = 1.seconds,
            factor: Double = 2.0,
            max: Duration = 60.seconds,
        ): RetryStrategy =
            RetryStrategy { attempt ->
                if (attempt > times) {
                    null
                } else {
                    minOf(initial * factor.pow(attempt - 1), max)
                }
            }

        /** Never gives up. Only for work whose failures are always transient, which is rarer than it looks. */
        public fun forever(delay: Duration = 1.seconds): RetryStrategy = RetryStrategy { delay }
    }
}
