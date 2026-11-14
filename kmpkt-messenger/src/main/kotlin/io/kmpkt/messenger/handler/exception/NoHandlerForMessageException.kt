package io.kmpkt.messenger.handler.exception

import io.kmpkt.messenger.message.Message

/** No handler is registered for [message]'s type, and [io.kmpkt.messenger.handler.HandlerPolicy] requires one. */
public class NoHandlerForMessageException(
    message: Message,
) : RuntimeException("No handler registered for message: ${message::class.simpleName}")
