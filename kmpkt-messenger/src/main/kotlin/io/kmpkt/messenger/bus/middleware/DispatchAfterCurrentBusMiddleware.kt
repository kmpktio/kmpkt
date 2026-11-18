package io.kmpkt.messenger.bus.middleware

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import io.kmpkt.messenger.stamp.DispatchAfterCurrentBusStamp
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

private class DispatchAfterCurrentBusContext(
    val queue: ConcurrentLinkedQueue<suspend () -> Envelope>,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DispatchAfterCurrentBusContext>
}

/**
 * Defers dispatches carrying [DispatchAfterCurrentBusStamp] until the enclosing outer bus
 * dispatch returns normally.
 *
 * When the outer dispatch throws, all buffered inner dispatches are discarded. Any further
 * deferred messages enqueued while draining the queue are also drained in FIFO order.
 */
public class DispatchAfterCurrentBusMiddleware : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        val active = coroutineContext[DispatchAfterCurrentBusContext]
        if (active != null) {
            if (envelope.last<DispatchAfterCurrentBusStamp>() != null) {
                active.queue.add { next(envelope) }
                return envelope
            }
            return next(envelope)
        }

        val queue = ConcurrentLinkedQueue<suspend () -> Envelope>()
        val result = withContext(DispatchAfterCurrentBusContext(queue)) { next(envelope) }
        // Re-attach the active context while draining so deferred messages dispatched
        // from a queued handler are also appended to this queue rather than running immediately.
        withContext(DispatchAfterCurrentBusContext(queue)) {
            while (true) {
                val deferred = queue.poll() ?: break
                deferred()
            }
        }
        return result
    }
}
