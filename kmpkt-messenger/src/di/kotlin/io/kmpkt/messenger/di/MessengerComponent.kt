package io.kmpkt.messenger.di

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.bus.DefaultMessageBus
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.di.pass.RegisterMessageHandlersPass
import io.kmpkt.messenger.handler.DefaultHandlerRegistry
import io.kmpkt.messenger.handler.HandlerRegistry
import io.kmpkt.messenger.handler.middleware.HandleMessageMiddleware
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.retry.RetryStrategy
import io.kmpkt.messenger.transport.SyncTransport
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.messenger.transport.middleware.SendMessageMiddleware
import io.kmpkt.messenger.transport.registry.DefaultTransportRegistry
import io.kmpkt.messenger.transport.registry.TransportRegistry
import java.time.Clock

/**
 * Configures the messenger: `messenger { bus("command") }`. Short for
 * `configure(MessengerComponent::class) { … }`.
 */
public fun ContainerBuilder.messenger(block: MessengerOptions.() -> Unit) {
    configure(MessengerComponent::class, block)
}

/**
 * Compile-time wiring for [io.kmpkt.messenger]: any number of transports and buses.
 *
 * ```kotlin
 * messenger {
 *     val failed = transport<InMemoryTransport>("failed")
 *     val sync = transport<SyncTransport>("sync") {
 *         retry<MultiplierRetryStrategy> { maxRetries = 1 }
 *         failureTransport = failed
 *     }
 *
 *     val command = bus("command") {
 *         handles<CommandHandler<*>>()
 *         defaultTransport = sync
 *         middleware<AccessCheckMiddleware>()
 *     }
 *     bus("event") {
 *         policy = HandlerPolicy.MULTIPLE
 *         handles<EventHandler<*>>()
 *     }
 *     defaultBus = command
 * }
 * ```
 *
 * **Buses.** Each is bound as `MessageBus` under its name, so a parameter `command: MessageBus` gets the
 * command bus; if [MessengerOptions.defaultBus] is set, that bus is bound without a name as well. Its registry
 * is bound as `HandlerRegistry` the same way. An application that configures no bus gets a single bus named
 * `default`, which is also bound without a name.
 *
 * **Transports.** A message goes to the transport its type is routed to, or else to the bus's default, which
 * unless said otherwise is `sync`: straight back to the same bus, handled inline. Retrying and putting
 * failed messages aside are configured on the transport. A queue transport is bound as
 * `Transport` under its name and its strategy as `RetryStrategy` under the same, which is where whoever runs
 * a worker for it finds them.
 *
 * **Handlers.** Any registered service that is a [MessageHandler]. Which message it handles is read off its
 * type argument; which bus it is on is the bus's `handles<…>()`, or [MessageHandlerTag.bus].
 *
 * Time is `Clock.systemUTC()` unless the application registers a `Clock` of its own.
 */
public class MessengerComponent : Component<MessengerOptions> {
    override fun defaults(): MessengerOptions = MessengerOptions()

    override fun ContainerBuilder.register(config: MessengerOptions) {
        autoconfigure<MessageHandler<*, *>> {
            tag(MessageHandlerTag())
        }
        addPass(RegisterMessageHandlersPass())

        // An application that configures no bus gets a single default bus named "default".
        // Once any bus is configured explicitly, an unnamed default binding is only created if `defaultBus` is set.
        val buses = config.buses.ifEmpty { mapOf(DEFAULT_BUS to BusOptions()) }
        val default = if (config.buses.isEmpty()) DEFAULT_BUS else config.defaultBus

        if (default != null) {
            require(default in buses) {
                "Default bus \"${default.name}\" was never configured; configured buses: ${buses.keys.joinToString { it.name }}."
            }
        }

        // A default: an application that registers a Clock of its own replaces this one, and a container
        // that never needs the time does not get it at all.
        factory(Clock::systemUTC)

        val transports = config.resolvedTransports()
        registerTransports(transports)
        buses.forEach { (busRef, bus) ->
            registerBus(busRef, bus, transports, isDefault = busRef == default)
        }
    }

    /** Registers queue transports and retry strategies once so multiple buses can share them. */
    private fun ContainerBuilder.registerTransports(transports: Map<TransportRef<*>, TransportDeclaration>) {
        transports.values.forEach { transport ->
            transport.retry?.let { retry ->
                service(ServiceDefinition(strategyId(transport), retry.strategy, arguments = retry.arguments))
                    .bind<RetryStrategy>(transport.name)
            }

            if (!transport.isSync) {
                service(transport.type, queueId(transport)) {
                    bind<Transport>(transport.name)
                }
            }

            val failure = transport.failure(transports)
            if (failure != null && failure.isSync) {
                diagnostics.error(
                    "The transport \"${transport.name}\" has \"${failure.name}\" as its failure transport, which is a " +
                        "sync transport: it would handle the failed message again rather than keep it.",
                )
            }
        }
    }

    /** Registers a single message bus along with its handler registry and middleware chain. */
    private fun ContainerBuilder.registerBus(
        busRef: BusRef,
        bus: BusOptions,
        transports: Map<TransportRef<*>, TransportDeclaration>,
        isDefault: Boolean,
    ) {
        val name = busRef.name
        val registry =
            service<DefaultHandlerRegistry>(ServiceId("messenger.bus.$name.handlers")) {
                tag(HandlerRegistryTag(name, bus.handles))
                bind<HandlerRegistry>(name)
                if (isDefault) bind<HandlerRegistry>()
            }
        val handle =
            service<HandleMessageMiddleware>(ServiceId("messenger.bus.$name.handle")) {
                argument<HandlerRegistry>(reference(registry.id))
                argument(bus.policy)
            }
        val send = sendingFor(busRef, bus, transports)

        service<DefaultMessageBus>(ServiceId("messenger.bus.$name")) {
            bind<MessageBus>(name)
            if (isDefault) bind<MessageBus>()
            // Routing first: a message that goes to a transport passes nothing
            // else on the way out. Then the bus's own middleware in the order given; handling is innermost.
            argument<List<Middleware>>(
                Argument.Sequence(
                    listOfNotNull(send) + bus.middleware.map { Argument.Service(ServiceReference.ByKey(it)) } + reference(handle.id),
                ),
            )
            argument<HandlerRegistry>(reference(registry.id))
        }
    }

    /**
     * Registers the middleware that routes this bus's messages to their transports, or returns `null` when
     * every message would only go through a plain sync transport and be handled inline.
     */
    private fun ContainerBuilder.sendingFor(
        busRef: BusRef,
        bus: BusOptions,
        transports: Map<TransportRef<*>, TransportDeclaration>,
    ): Argument? {
        val fallback = transports.getValue(bus.defaultTransport ?: SYNC_TRANSPORT)
        val isPlainlyInline = bus.routes.isEmpty() && fallback.isSync && fallback.retry == null && fallback.failure(transports) == null
        if (isPlainlyInline) return null

        val name = busRef.name
        val used = (bus.routes.values.map(transports::getValue) + fallback).distinctBy { it.ref }
        val routes =
            service<DefaultTransportRegistry>(ServiceId("messenger.bus.$name.routes")) {
                argument(
                    "routes",
                    Argument.Dictionary(
                        bus.routes.map { (message, transport) ->
                            Argument.Literal(ConstValue.ClassLiteral(message)) to literal(transport.name)
                        },
                    ),
                )
                argument("fallback", literal(fallback.name))
            }

        val transportEntries = used.map { literal(it.name) to transportOf(busRef, it, transports) }
        val send =
            service<SendMessageMiddleware>(ServiceId("messenger.bus.$name.send")) {
                argument<TransportRegistry>(reference(routes.id))
                argument("transports", Argument.Dictionary(transportEntries))
            }

        return reference(send.id)
    }

    /** Resolves a transport declaration to the [Argument] passed into [SendMessageMiddleware]. */
    private fun ContainerBuilder.transportOf(
        busRef: BusRef,
        transport: TransportDeclaration,
        transports: Map<TransportRef<*>, TransportDeclaration>,
    ): Argument {
        if (!transport.isSync) return reference(queueId(transport))

        val bus = busRef.name
        return reference(
            service<SyncTransport>(ServiceId("messenger.bus.$bus.transport.${transport.name}")) {
                // Lazy: the bus is built with this transport already in its chain.
                argument(
                    "messageBus",
                    Argument.Deferred(ServiceReference.ById(ServiceId("messenger.bus.$bus")), Argument.Deferred.Kind.Lazy),
                )
                argument(transport.name)
                argument("retryStrategy", transport.retry?.let { reference(strategyId(transport)) } ?: NULL)
                argument("failureTransport", transport.failure(transports)?.let { reference(queueId(it)) } ?: NULL)
            }.id,
        )
    }

    /**
     * Ensures the implicit `sync` transport exists and resolves each transport's effective failure
     * transport (falling back to the global [MessengerOptions.failureTransport], never to itself).
     */
    private fun MessengerOptions.resolvedTransports(): Map<TransportRef<*>, TransportDeclaration> {
        val withSync =
            if (SYNC_TRANSPORT in transports) {
                transports
            } else {
                transports + (SYNC_TRANSPORT to TransportDeclaration(SYNC_TRANSPORT, SYNC_TYPE))
            }
        return withSync.mapValues { (ref, declaration) ->
            val effectiveFailure = (declaration.failureTransport ?: failureTransport)?.takeIf { it != ref }
            declaration.copy(failureTransport = effectiveFailure)
        }
    }

    private fun TransportDeclaration.failure(transports: Map<TransportRef<*>, TransportDeclaration>): TransportDeclaration? =
        failureTransport?.let(transports::getValue)

    private val TransportDeclaration.isSync: Boolean get() = type == SYNC_TYPE

    private fun queueId(transport: TransportDeclaration) = ServiceId("messenger.transport.${transport.name}")

    private fun strategyId(transport: TransportDeclaration) = ServiceId("messenger.transport.${transport.name}.retry")

    private companion object {
        val DEFAULT_BUS = BusRef("default")
        val SYNC_TRANSPORT = TransportRef<SyncTransport>("sync")
        val SYNC_TYPE = typeRef<SyncTransport>()
        val NULL = Argument.Literal(ConstValue.Null)

        fun reference(id: ServiceId): Argument = Argument.Service(ServiceReference.ById(id))
    }
}
