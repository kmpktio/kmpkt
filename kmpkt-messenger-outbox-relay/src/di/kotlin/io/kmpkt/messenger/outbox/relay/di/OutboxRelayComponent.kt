package io.kmpkt.messenger.outbox.relay.di

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.outbox.relay.OutboxRelayMiddleware
import io.kmpkt.messenger.transport.Transport
import kotlin.reflect.KClass

/**
 * Configures outbox relay middlewares: `outboxRelay { relay(from = outbox, to = nats) }`.
 */
public fun ContainerBuilder.outboxRelay(block: OutboxRelayOptions.() -> Unit) {
    configure(OutboxRelayComponent::class, block)
}

/**
 * Registers [OutboxRelayMiddleware] instances (bound as [Middleware] under `<from>-to-<to>`) for
 * each declared relay.
 */
public class OutboxRelayComponent : Component<OutboxRelayOptions> {
    override val requires: Set<KClass<out Component<*>>> = setOf(MessengerComponent::class)

    override fun defaults(): OutboxRelayOptions = OutboxRelayOptions()

    override fun ContainerBuilder.register(config: OutboxRelayOptions) {
        config.relays.forEach { relay ->
            val qualifier = "${relay.from.name}-to-${relay.to.name}"
            service<OutboxRelayMiddleware>(ServiceId("outbox.relay.$qualifier")) {
                bind<Middleware>(qualifier)
                argument("sourceName", literal(relay.from.name))
                argument("target", byKey<Transport>(relay.to.name))
            }
        }
    }
}

private inline fun <reified T : Any> byKey(qualifier: String): Argument =
    Argument.Service(ServiceReference.ByKey(ServiceKey(typeRef<T>(), qualifier)))
