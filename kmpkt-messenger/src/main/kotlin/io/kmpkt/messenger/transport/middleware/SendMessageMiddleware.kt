package io.kmpkt.messenger.transport.middleware

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.messenger.transport.routing.TransportRouter
import io.kmpkt.messenger.transport.stamp.SentStamp
import java.time.Clock

/**
 * Routes an outbound message via [transportRouter] instead of handling it inline whenever a target
 * [Transport] is matched. Envelopes carrying [ReceivedStamp] pass straight through so messages coming in
 * from a transport are never re-routed back out.
 */
public class SendMessageMiddleware(
    private val transportRouter: TransportRouter,
    private val clock: Clock,
) : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        if (envelope.last<ReceivedStamp>() != null) return next(envelope)

        val routed = transportRouter.route(envelope) ?: return next(envelope)

        return routed.transport.send(envelope.with(SentStamp(routed.transportName, clock.instant())))
    }
}
