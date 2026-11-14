package io.kmpkt.query

import io.kmpkt.messenger.message.Message

/**
 * A request to read state without changing it, returning a result of type [R].
 *
 * A query is a [Message]: it goes over a [QueryBus], which is the only way it is ever answered.
 */
public interface Query<out R> : Message
