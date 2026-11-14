package io.kmpkt.messenger.bus

import io.kmpkt.messenger.handler.stamp.HandledStamp
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message

/**
 * Dispatches [Message]s through a chain of middleware to whatever handles them.
 *
 * Immutable: the handlers are settled when the bus is built, which is why there is no `register` here. A bus
 * that could be registered with afterwards made every decorator forward a method it had no business in, and
 * made "is this bus fully wired yet" a question with no answer.
 */
public interface MessageBus {
    /**
     * Dispatches [message] and returns the [Envelope] as the chain left it.
     *
     * Not the handler's result: a message may be routed to a transport, in which case nothing has handled it
     * yet and there is no result to return. Where the dispatch really is synchronous, the result is on the
     * envelope - `envelope.last<HandledStamp>()?.result` - and reading it is an admission that this particular
     * message is handled inline.
     */
    public suspend fun dispatch(message: Message): Envelope

    /** Dispatches [envelope] and returns it as left by the middleware chain (stamps added, etc.). */
    public suspend fun dispatch(envelope: Envelope): Envelope

    /**
     * Dispatches [message] and throws the envelope away.
     *
     * For a command, whose only answer is "accepted". Exists so application code never has to name an
     * [Envelope] - the envelope is the bus's bookkeeping, and letting it into a domain service or an HTTP
     * route spreads a messaging concept into places that have no business knowing about one.
     */
    public suspend fun send(message: Message) {
        dispatch(message)
    }

    /**
     * Dispatches [message] and returns its handler's result.
     *
     * **Only meaningful for a message handled inline.** One routed to a transport has not been handled when
     * this returns, so there is nothing to give back and this fails rather than inventing a value. Calling it
     * is the caller stating that this particular message is synchronous.
     */
    @Suppress("UNCHECKED_CAST")
    public suspend fun <R> ask(message: Message): R {
        val envelope = dispatch(message)

        return envelope.last<HandledStamp>()?.result as? R
            ?: error(
                "No handler result for ${message::class.simpleName}: either nothing handled it, or it was " +
                    "routed to a transport and will be handled later - ask() only works for a message " +
                    "handled inline",
            )
    }
}
