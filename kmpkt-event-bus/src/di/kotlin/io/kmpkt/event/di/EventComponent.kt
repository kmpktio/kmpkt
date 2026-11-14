package io.kmpkt.event.di

import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.di.compiler.service
import io.kmpkt.event.EventBus
import io.kmpkt.event.EventHandler
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.di.BusOptions
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.messenger.di.MessengerOptions
import io.kmpkt.messenger.handler.HandlerPolicy
import kotlin.reflect.KClass

private const val EVENT_BUS = "event"

/** Adds to the event bus this component declares, the way `commandBus` does for commands. */
public fun MessengerOptions.eventBus(configure: BusOptions.() -> Unit) {
    bus(EVENT_BUS, configure)
}

/**
 * Compile-time wiring for [io.kmpkt.event]: an [EventBus] an application publishes on, over a message bus
 * that takes exactly the [EventHandler]s - any number per event, none included.
 *
 * Declared by prepending to the messenger's options, like the command bus. An application asks for
 * `EventBus`, never for the message bus underneath.
 */
public class EventComponent : SimpleComponent {
    override val requires: Set<KClass<out Component<*>>> = setOf(MessengerComponent::class)

    override fun ContainerBuilder.prepare() {
        prepend(MessengerComponent::class) {
            eventBus {
                policy = HandlerPolicy.MULTIPLE
                handles<EventHandler<*>>()
            }
        }
    }

    override fun ContainerBuilder.register() {
        service<EventBus> {
            argument(service<MessageBus>(EVENT_BUS))
        }
    }
}
