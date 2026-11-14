package io.kmpkt.messenger.handler

import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import kotlin.reflect.KClass

/**
 * Always allows multiple handlers per message type; [HandlerPolicy] is enforced by the middleware, not here.
 *
 * [handlers] is what the registry starts out with - everything, when the wiring knows every handler up
 * front. [register] adds to it afterwards.
 */
public class DefaultHandlerRegistry(
    handlers: Map<KClass<out Message>, List<MessageHandler<*, *>>> = emptyMap(),
) : HandlerRegistry {
    private val handlers: MutableMap<KClass<out Message>, MutableList<MessageHandler<*, *>>> =
        handlers.mapValuesTo(mutableMapOf()) { (_, registered) -> registered.toMutableList() }

    override fun <M : Message, R> register(
        messageType: KClass<M>,
        handler: MessageHandler<M, R>,
    ) {
        handlers.getOrPut(messageType) { mutableListOf() }.add(handler)
    }

    override fun getHandlers(messageType: KClass<out Message>): List<MessageHandler<*, *>> = handlers[messageType].orEmpty()
}
