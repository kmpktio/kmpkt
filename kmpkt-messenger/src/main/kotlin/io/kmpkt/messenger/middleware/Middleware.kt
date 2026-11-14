package io.kmpkt.messenger.middleware

import io.kmpkt.messenger.message.Envelope

public typealias MiddlewareChain = suspend (Envelope) -> Envelope

/**
 * One link in the [io.kmpkt.messenger.bus.MessageBus]'s dispatch chain: can inspect/stamp the envelope,
 * run logic before or after the rest of the chain, short-circuit it, or translate a failure - by calling (or
 * not calling) [next].
 */
public fun interface Middleware {
    public suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope
}
