package io.kmpkt.messenger.handler

import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import kotlin.reflect.KClass

/** Looks up the registered [MessageHandler]s for a [Message] type. */
public interface HandlerRegistry {
    public fun <M : Message, R> register(
        messageType: KClass<M>,
        handler: MessageHandler<M, R>,
    )

    public fun getHandlers(messageType: KClass<out Message>): List<MessageHandler<*, *>>
}
