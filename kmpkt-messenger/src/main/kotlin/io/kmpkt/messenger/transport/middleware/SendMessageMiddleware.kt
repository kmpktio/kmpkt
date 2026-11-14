package io.kmpkt.messenger.transport.middleware

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.messenger.transport.registry.TransportRegistry
import io.kmpkt.messenger.transport.stamp.RouteStamp
import io.kmpkt.messenger.transport.stamp.SentStamp
import java.time.Clock

/**
 * Routes a message to a [Transport] instead of handling it inline, if [transportRegistry] (or a per-envelope
 * [RouteStamp] override) names one for it. An envelope with no route at all stays on this bus, as does one
 * already carrying a [ReceivedStamp] - it came *from* a transport, and re-routing it would send it straight
 * back out again, forever.
 *
 * The [SentStamp] goes on *before* the transport sees the envelope, so a transport can tell which name it was
 * routed under, and whatever the transport returns is passed back up - that's what lets a message routed to a
 * [io.kmpkt.messenger.transport.SyncTransport] still return its handler's result to the original
 * `dispatch` call.
 */
public class SendMessageMiddleware(
    private val transportRegistry: TransportRegistry,
    // TODO move this to the transport registry
    private val transports: Map<String, Transport>,
    private val clock: Clock,
) : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        if (envelope.last<ReceivedStamp>() != null) return next(envelope)

        val transportName = envelope.last<RouteStamp>()?.transportName ?: transportRegistry.getTransport(envelope.message::class)
        if (transportName == null) return next(envelope)

        val transport =
            transports[transportName]
                ?: error("Transport '$transportName' not found - registered: ${transports.keys.sorted()}")

        return transport.send(envelope.with(SentStamp(transportName, clock.instant())))
    }
}
