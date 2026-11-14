package io.kmpkt.query

import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.handler.exception.HandlerFailedException

/**
 * Asks a [Query] on a [MessageBus] and returns the handler's result, so queries get that bus's middleware
 * and logging without exposing [io.kmpkt.messenger.message.Envelope] to callers.
 */
public class QueryBus(
    private val bus: MessageBus,
) {
    public suspend fun <R> ask(query: Query<R>): R =
        try {
            bus.ask(query)
        } catch (failure: HandlerFailedException) {
            throw failure.failures.singleOrNull() ?: failure
        }
}
