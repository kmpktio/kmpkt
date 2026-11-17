package io.kmpkt.messenger.bus.middleware

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import io.kmpkt.messenger.stamp.BusNameStamp

/**
 * Attaches [BusNameStamp] with [busName] to envelopes that do not already carry one.
 */
public class AddBusNameStampMiddleware(
    private val busName: String,
) : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        val stamped =
            if (envelope.last<BusNameStamp>() == null) {
                envelope.with(BusNameStamp(busName))
            } else {
                envelope
            }
        return next(stamped)
    }
}
