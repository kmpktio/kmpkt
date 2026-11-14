package io.kmpkt.worker

/**
 * One pass of something a [Worker] can keep doing: poll a transport, bring a subscription up to date, drain a
 * queue. Returns how many units it handled, so the worker knows whether there was anything to do - `0` means
 * idle, and the worker waits before asking again.
 *
 * Throwing is how a pass reports that it could not finish; the [Worker]'s [RetryStrategy] decides what happens
 * next. Anything the work itself knows how to recover from - one bad message among many, say - it should deal
 * with inside a pass, and count the rest.
 */
public fun interface Work {
    public suspend fun run(): Int
}
