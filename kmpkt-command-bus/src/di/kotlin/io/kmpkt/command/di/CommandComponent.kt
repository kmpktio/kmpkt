package io.kmpkt.command.di

import io.kmpkt.command.CommandBus
import io.kmpkt.command.CommandHandler
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.di.compiler.service
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.di.BusOptions
import io.kmpkt.messenger.di.BusRef
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.messenger.di.MessengerOptions
import io.kmpkt.messenger.handler.HandlerPolicy
import kotlin.reflect.KClass

private const val COMMAND_BUS = "command"

/**
 * Adds to the command bus this component declares - a transport to go through, a middleware:
 *
 * ```kotlin
 * container.messenger {
 *     commandBus {
 *         middleware<AccessCheckMiddleware>()
 *     }
 * }
 * ```
 */
public fun MessengerOptions.commandBus(configure: BusOptions.() -> Unit): BusRef =
    bus(COMMAND_BUS, configure)

/**
 * Compile-time wiring for [io.kmpkt.command]: a [CommandBus] an application dispatches on, over a message
 * bus that takes exactly the [CommandHandler]s, one per command.
 *
 * The bus is declared by prepending to the messenger's options, so it is there before the application's own
 * configuration runs and [commandBus] only ever adds to it. An application asks for `CommandBus`, never for
 * the message bus underneath.
 */
public class CommandComponent : SimpleComponent {
    override val requires: Set<KClass<out Component<*>>> = setOf(MessengerComponent::class)

    override fun ContainerBuilder.prepare() {
        prepend(MessengerComponent::class) {
            commandBus {
                policy = HandlerPolicy.SINGLE
                handles<CommandHandler<*>>()
            }
        }
    }

    override fun ContainerBuilder.register() {
        service<CommandBus> {
            argument(service<MessageBus>(COMMAND_BUS))
        }
    }
}
