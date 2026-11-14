package io.kmpkt.query

import io.kmpkt.messenger.message.MessageHandler

/**
 * Answers one kind of [Query] with a result of type [R]. Contravariant in [Q], so a handler written against
 * a query's supertype also serves its subtypes.
 */
public interface QueryHandler<in Q : Query<R>, R> : MessageHandler<Q, R> {
    public suspend fun handle(query: Q): R

    /** What the bus calls. A query handler is the message handler for its query. */
    override suspend fun invoke(message: Q): R = handle(message)
}
