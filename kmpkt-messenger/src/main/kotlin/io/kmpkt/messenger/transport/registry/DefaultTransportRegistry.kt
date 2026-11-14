package io.kmpkt.messenger.transport.registry

import io.kmpkt.messenger.message.Message
import kotlin.reflect.KClass

/**
 * Static message-type-to-transport-name routing table, configured once at construction.
 *
 * [fallback] is where every message type without a route of its own goes. Left out,
 * such a message is handled inline.
 */
public class DefaultTransportRegistry(
    private val routes: Map<KClass<out Message>, String> = emptyMap(),
    private val fallback: String? = null,
) : TransportRegistry {
    override fun getTransport(messageType: KClass<out Message>): String? = routes[messageType] ?: fallback
}
