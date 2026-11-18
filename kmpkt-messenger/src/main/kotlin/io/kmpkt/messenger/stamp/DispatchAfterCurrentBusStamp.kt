package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Stamp

/**
 * Marks a message whose downstream handling/sending should be deferred by
 * [io.kmpkt.messenger.bus.middleware.DispatchAfterCurrentBusMiddleware] until the
 * currently executing outer bus dispatch completes without throwing.
 */
public class DispatchAfterCurrentBusStamp : Stamp {
    override fun equals(other: Any?): Boolean = other is DispatchAfterCurrentBusStamp

    override fun hashCode(): Int = DispatchAfterCurrentBusStamp::class.hashCode()
}
