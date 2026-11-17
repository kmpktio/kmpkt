package io.kmpkt.messenger.outbox.relay

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.transport.Transport

/**
 * Bus [Middleware] that forwards envelopes received from [sourceName] (marked with [ReceivedStamp])
 * directly to [target] instead of passing them to downstream handlers.
 *
 * Designed to run on a relay bus behind [io.kmpkt.messenger.retry.middleware.RetryMiddleware] and
 * consumed by [io.kmpkt.messenger.worker.TransportWork] (optionally wrapped in
 * [io.kmpkt.worker.LockedWork] for single-active-relay ordering).
 */
public class OutboxRelayMiddleware(
    private val sourceName: String,
    private val target: Transport,
) : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        if (envelope.last<ReceivedStamp>()?.transportName != sourceName) {
            return next(envelope)
        }

        return target.send(envelope)
    }
}
