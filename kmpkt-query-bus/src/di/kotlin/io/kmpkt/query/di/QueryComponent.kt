package io.kmpkt.query.di

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
import io.kmpkt.query.QueryBus
import io.kmpkt.query.QueryHandler
import kotlin.reflect.KClass

private const val QUERY_BUS = "query"

/**
 * Adds to the query bus this component declares — middleware, transport, etc.:
 *
 * ```kotlin
 * messenger {
 *     queryBus {
 *         middleware<LoggingMiddleware>()
 *     }
 * }
 * ```
 */
public fun MessengerOptions.queryBus(configure: BusOptions.() -> Unit): BusRef =
    bus(QUERY_BUS, configure)

/**
 * Compile-time wiring for [io.kmpkt.query]: a [QueryBus] an application asks on, over a message
 * bus that takes exactly one [QueryHandler] per query.
 */
public class QueryComponent : SimpleComponent {
    override val requires: Set<KClass<out Component<*>>> = setOf(MessengerComponent::class)

    override fun ContainerBuilder.prepare() {
        prepend(MessengerComponent::class) {
            queryBus {
                policy = HandlerPolicy.SINGLE
                handles<QueryHandler<*, *>>()
            }
        }
    }

    override fun ContainerBuilder.register() {
        service<QueryBus> {
            argument(service<MessageBus>(QUERY_BUS))
        }
    }
}
