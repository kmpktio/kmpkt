package io.kmpkt.messenger.transport.routing

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.transport.registry.TransportRegistry
import io.kmpkt.messenger.transport.stamp.RouteStamp
import kotlin.reflect.KClass

/**
 * Default [TransportRouter] matching per-envelope [RouteStamp] overrides first, then [routes] by exact
 * message class, and finally [fallback], resolving the target from [transportRegistry].
 */
public class DefaultTransportRouter(
    private val transportRegistry: TransportRegistry,
    private val routes: Map<KClass<out Message>, String> = emptyMap(),
    private val fallback: String? = null,
) : TransportRouter {
    override fun route(envelope: Envelope): RoutedTransport? {
        val name =
            envelope.last<RouteStamp>()?.transportName
                ?: routes[envelope.message::class]
                ?: fallback
                ?: return null
        return RoutedTransport(name, transportRegistry.get(name))
    }
}
