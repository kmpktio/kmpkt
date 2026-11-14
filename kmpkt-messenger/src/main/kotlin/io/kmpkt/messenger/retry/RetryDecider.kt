package io.kmpkt.messenger.retry

import io.kmpkt.messenger.handler.exception.HandlerFailedException
import io.kmpkt.messenger.message.Envelope

/**
 * What a failure says about itself, before any strategy is asked.
 *
 * A handler's failure arrives wrapped in a [HandlerFailedException], so the markers are looked for inside it:
 * one recoverable failure among several is reason enough to retry, and only if every one of them is
 * unrecoverable is the whole thing.
 */
public object RetryDecider {
    /** `true` to retry, `false` not to, `null` when the failure carries no marker and the strategy decides. */
    public fun decideFrom(failure: Throwable): Boolean? {
        if (failure is RecoverableException) return true

        if (failure is HandlerFailedException) {
            if (failure.failures.any { it is RecoverableException }) return true
            if (failure.failures.isNotEmpty() && failure.failures.all { it is UnrecoverableException }) return false
        }

        return if (failure is UnrecoverableException) false else null
    }

    /** For a queued message: the failure's own word first, the strategy's otherwise. */
    public fun shouldRetry(
        failure: Throwable,
        envelope: Envelope,
        strategy: RetryStrategy,
    ): Boolean = decideFrom(failure) ?: strategy.isRetryable(envelope, failure)
}
