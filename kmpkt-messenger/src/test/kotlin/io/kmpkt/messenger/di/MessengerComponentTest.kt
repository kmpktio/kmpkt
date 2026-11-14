package io.kmpkt.messenger.di

import io.kmpkt.di.KmpKtApplication
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.CollectingDiagnostics
import io.kmpkt.di.compiler.CompiledContainer
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ContainerCompiler
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.fixture.FakeTypeOracle
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.di.fixture.declareMessenger
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.handler.HandlerRegistry
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import io.kmpkt.messenger.retry.MultiplierRetryStrategy
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.messenger.transport.SyncTransport
import io.kmpkt.messenger.transport.Transport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

private class Ping : Message

private class Pong : Message

private class PingHandler : MessageHandler<Ping, Unit> {
    override suspend fun invoke(message: Ping) = Unit
}

private class OtherPingHandler : MessageHandler<Ping, Unit> {
    override suspend fun invoke(message: Ping) = Unit
}

private class PongHandler : MessageHandler<Pong, Unit> {
    override suspend fun invoke(message: Pong) = Unit
}

private class Outer : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope = next(envelope)
}

private class Inner : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope = next(envelope)
}

private interface CommandLike<M : Message> : MessageHandler<M, Unit>

private interface EventLike<M : Message> : MessageHandler<M, Unit>

private class CommandLikeHandler : CommandLike<Ping> {
    override suspend fun invoke(message: Ping) = Unit
}

private class EventLikeHandler : EventLike<Ping> {
    override suspend fun invoke(message: Ping) = Unit
}

private class Queue : Transport {
    override suspend fun send(envelope: Envelope): Envelope = envelope

    override suspend fun receive(): List<Envelope> = emptyList()

    override suspend fun ack(envelope: Envelope) = Unit

    override suspend fun reject(envelope: Envelope) = Unit
}

private fun byId(id: String): Argument = Argument.Service(ServiceReference.ById(ServiceId(id)))

private fun classLiteral(type: KClass<*>): Argument =
    Argument.Literal(
        ConstValue.ClassLiteral(
            io.kmpkt.di.compiler
                .TypeRef(type.qualifiedName!!),
        ),
    )

/** The component through the whole pipeline, over a fake of what KSP would know about the messenger's classes. */
private class Compilation(
    vararg entryPoints: Pair<String, ServiceKey>,
    configure: (ContainerBuilder) -> Unit = {},
) {
    private val types =
        FakeTypeOracle().apply {
            declareMessenger()
            declare<CommandLikeHandler>(
                supertypes = listOf(typeRef<CommandLike<Ping>>(), typeRef<MessageHandler<Ping, Unit>>()),
            )
            declare<EventLikeHandler>(
                supertypes = listOf(typeRef<EventLike<Ping>>(), typeRef<MessageHandler<Ping, Unit>>()),
            )
            declare<Queue>(supertypes = listOf(typeRef<Transport>()))
            declare<PingHandler>(supertypes = listOf(typeRef<MessageHandler<Ping, Unit>>()))
            declare<OtherPingHandler>(supertypes = listOf(typeRef<MessageHandler<Ping, Unit>>()))
            declare<PongHandler>(supertypes = listOf(typeRef<MessageHandler<Pong, Unit>>()))
            declare<Outer>(supertypes = listOf(typeRef<Middleware>()))
            declare<Inner>(supertypes = listOf(typeRef<Middleware>()))
        }
    private val diagnostics = CollectingDiagnostics()

    val errors: List<String> get() = diagnostics.errors
    val compiled: CompiledContainer? =
        ContainerBuilder(types, diagnostics)
            .apply { entryPoints.forEach { (name, key) -> entryPoint(name, key) } }
            .let {
                ContainerCompiler(
                    listOf(MessengerComponent()),
                    listOf(Configuration<KmpKtApplication.Default> { configure(this) }),
                ).compile(it)
            }

    fun arguments(id: String): Map<String, Argument> = compiled!!.definition(ServiceId(id)).arguments

    fun entryPoint(name: String): String? =
        compiled
            ?.entryPoints
            ?.single { it.name == name }
            ?.id
            ?.value
}

private fun bus(name: String? = null) = ServiceKey(typeRef<MessageBus>(), name)

private fun registry(name: String? = null) = ServiceKey(typeRef<HandlerRegistry>(), name)

class MessengerComponentTest :
    FunSpec({
        test("an application that configures nothing has one bus, named default and bound without a name as well") {
            // Act
            val compilation = Compilation("bus" to bus(), "named" to bus("default"), "handlers" to registry())

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.entryPoint("bus") shouldBe "messenger.bus.default"
            compilation.entryPoint("named") shouldBe "messenger.bus.default"
            compilation.entryPoint("handlers") shouldBe "messenger.bus.default.handlers"
            compilation.arguments("messenger.bus.default") shouldBe
                mapOf(
                    "middleware" to Argument.Sequence(listOf(byId("messenger.bus.default.handle"))),
                    "handlerRegistry" to byId("messenger.bus.default.handlers"),
                )
            compilation.arguments("messenger.bus.default.handle") shouldBe
                mapOf(
                    "handlers" to byId("messenger.bus.default.handlers"),
                    "policy" to literal(HandlerPolicy.SINGLE),
                    "clock" to byId("java.time.Clock"),
                )
        }

        test("every configured bus gets its own registry, policy and chain, in the order its middleware was named") {
            // Act
            val compilation =
                Compilation("command" to bus("command"), "event" to bus("event")) { container ->
                    container.configure(MessengerComponent::class) {
                        bus("command") {
                            middleware<Outer>()
                            middleware<Inner>()
                        }
                        bus("event") {
                            policy = HandlerPolicy.MULTIPLE
                        }
                        // Declared again: adds to the bus rather than replacing it.
                        bus("event") {
                            middleware<Inner>()
                        }
                    }
                    container.service<Outer>()
                    container.service<Inner>()
                }

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.arguments("messenger.bus.command") shouldBe
                mapOf(
                    "middleware" to
                        Argument.Sequence(
                            listOf(
                                byId(Outer::class.qualifiedName!!),
                                byId(Inner::class.qualifiedName!!),
                                byId("messenger.bus.command.handle"),
                            ),
                        ),
                    "handlerRegistry" to byId("messenger.bus.command.handlers"),
                )
            compilation.arguments("messenger.bus.event") shouldBe
                mapOf(
                    "middleware" to Argument.Sequence(listOf(byId(Inner::class.qualifiedName!!), byId("messenger.bus.event.handle"))),
                    "handlerRegistry" to byId("messenger.bus.event.handlers"),
                )
            compilation.arguments("messenger.bus.command.handle")["policy"] shouldBe literal(HandlerPolicy.SINGLE)
            compilation.arguments("messenger.bus.event.handle")["policy"] shouldBe literal(HandlerPolicy.MULTIPLE)
        }

        test("several buses have no default unless one is named") {
            // Arrange
            val twoBuses: MessengerOptions.() -> Unit = {
                bus("command")
                bus("event")
            }

            // Act
            val without = Compilation("bus" to bus()) { it.configure(MessengerComponent::class, twoBuses) }
            val with =
                Compilation("bus" to bus(), "handlers" to registry()) {
                    it.configure(MessengerComponent::class, twoBuses)
                    it.configure(MessengerComponent::class) {
                        defaultBus = BusRef("event")
                    }
                }

            // Assert
            without.compiled.shouldBeNull()
            without.errors shouldContainExactly
                listOf("Entry point \"bus\" asks for io.kmpkt.messenger.bus.MessageBus, but no service is bound to that.")
            with.entryPoint("bus") shouldBe "messenger.bus.event"
            with.entryPoint("handlers") shouldBe "messenger.bus.event.handlers"
        }

        test("a default bus that was never configured is an error") {
            // Act & Assert
            shouldThrow<IllegalArgumentException> {
                Compilation {
                    it.configure(MessengerComponent::class) {
                        bus("command")
                        defaultBus = BusRef("comand")
                    }
                }
            }.message shouldContain "comand"
        }

        test("a handler is found by being a MessageHandler, routed by its message type, on every bus unless it names one") {
            // Act
            val compilation =
                Compilation("command" to bus("command"), "event" to bus("event")) { container ->
                    container.configure(MessengerComponent::class) {
                        bus("command")
                        bus("event")
                    }
                    container.service<PingHandler>()
                    container.service<OtherPingHandler> {
                        tag(MessageHandlerTag(bus = "event", priority = 10))
                    }
                    container.service<PongHandler> {
                        tag(MessageHandlerTag(bus = "command"))
                    }
                }

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.arguments("messenger.bus.command.handlers") shouldBe
                mapOf(
                    "handlers" to
                        Argument.Dictionary(
                            listOf(
                                classLiteral(Ping::class) to Argument.Sequence(listOf(byId(PingHandler::class.qualifiedName!!))),
                                classLiteral(Pong::class) to Argument.Sequence(listOf(byId(PongHandler::class.qualifiedName!!))),
                            ),
                        ),
                )
            compilation.arguments("messenger.bus.event.handlers") shouldBe
                mapOf(
                    "handlers" to
                        Argument.Dictionary(
                            listOf(
                                classLiteral(Ping::class) to
                                    Argument.Sequence(
                                        listOf(byId(OtherPingHandler::class.qualifiedName!!), byId(PingHandler::class.qualifiedName!!)),
                                    ),
                            ),
                        ),
                )
        }

        test("a bus without handlers gets an empty map") {
            // Act
            val compilation = Compilation("bus" to bus())

            // Assert
            compilation.arguments("messenger.bus.default.handlers") shouldBe mapOf("handlers" to Argument.Dictionary(emptyList()))
        }

        test("a handler for a bus that does not exist, or of no recognisable message, fails the compilation") {
            // Act
            val compilation =
                Compilation("bus" to bus()) { container ->
                    container.service<PingHandler> {
                        tag(MessageHandlerTag(bus = "comand"))
                    }
                    // Tagged by hand, but not a MessageHandler at all.
                    container.service<Outer> {
                        tag(MessageHandlerTag())
                    }
                }

            // Assert
            compilation.compiled.shouldBeNull()
            compilation.errors shouldContainExactly
                listOf(
                    "\"${PingHandler::class.qualifiedName}\" handles messages on the bus \"comand\", which is not configured. " +
                        "There is: default.",
                    "Cannot tell which message \"${Outer::class.qualifiedName}\" handles: its type ${Outer::class.qualifiedName} " +
                        "does not say what the M of MessageHandler<M, R> is.",
                )
        }

        test("handles puts each kind of handler on its own bus; a handler that names a bus goes there regardless") {
            // Act
            val compilation =
                Compilation("command" to bus("command"), "event" to bus("event"), "other" to bus("other")) { container ->
                    container.messenger {
                        bus("command") {
                            handles<CommandLike<*>>()
                        }
                        bus("event") {
                            handles<EventLike<*>>()
                        }
                        // Says nothing: takes every handler that does not name a bus.
                        bus("other")
                    }
                    container.service<CommandLikeHandler>()
                    container.service<EventLikeHandler>()
                    container.service<PongHandler> {
                        tag(MessageHandlerTag(bus = "command"))
                    }
                }

            // Assert
            fun handlersOf(bus: String) =
                (compilation.arguments("messenger.bus.$bus.handlers").getValue("handlers") as Argument.Dictionary)
                    .entries
                    .flatMap { (_, handlers) -> (handlers as Argument.Sequence).items }

            compilation.errors.shouldBeEmpty()
            handlersOf("command") shouldContainExactly
                listOf(byId(CommandLikeHandler::class.qualifiedName!!), byId(PongHandler::class.qualifiedName!!))
            handlersOf("event") shouldContainExactly listOf(byId(EventLikeHandler::class.qualifiedName!!))
            handlersOf("other") shouldContainExactly
                listOf(byId(CommandLikeHandler::class.qualifiedName!!), byId(EventLikeHandler::class.qualifiedName!!))
        }

        test("a bus that routes gets a sending middleware in front, a routing table, the queue and a sync transport of its own") {
            // Act
            val compilation =
                Compilation("bus" to bus("command"), "async" to ServiceKey(typeRef<Transport>(), "async")) { container ->
                    container.messenger {
                        val async = transport<Queue>("async")

                        bus("command") {
                            route<Ping>(async)
                            middleware<Outer>()
                        }
                    }
                    container.service<Outer>()
                }

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.entryPoint("async") shouldBe "messenger.transport.async"
            compilation.arguments("messenger.bus.command")["middleware"] shouldBe
                Argument.Sequence(
                    listOf(byId("messenger.bus.command.send"), byId(Outer::class.qualifiedName!!), byId("messenger.bus.command.handle")),
                )
            compilation.arguments("messenger.bus.command.send") shouldBe
                mapOf(
                    "transports" to
                        Argument.Dictionary(
                            listOf(
                                literal("async") to byId("messenger.transport.async"),
                                literal("sync") to byId("messenger.bus.command.transport.sync"),
                            ),
                        ),
                    "transportRegistry" to byId("messenger.bus.command.routes"),
                    "clock" to byId("java.time.Clock"),
                )
            compilation.arguments("messenger.bus.command.routes") shouldBe
                mapOf(
                    "routes" to Argument.Dictionary(listOf(classLiteral(Ping::class) to literal("async"))),
                    "fallback" to literal("sync"),
                )
            compilation.arguments("messenger.bus.command.transport.sync") shouldBe
                mapOf(
                    "messageBus" to
                        Argument.Deferred(ServiceReference.ById(ServiceId("messenger.bus.command")), Argument.Deferred.Kind.Lazy),
                    "retryStrategy" to Argument.Literal(ConstValue.Null),
                    "failureTransport" to Argument.Literal(ConstValue.Null),
                    "name" to literal("sync"),
                )
        }

        test("a bus's default transport is where everything without a route goes, a queue included") {
            // Act
            val compilation =
                Compilation("bus" to bus("command")) { container ->
                    container.messenger {
                        val async = transport<Queue>("async")

                        bus("command") {
                            defaultTransport = async
                        }
                    }
                }

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.arguments("messenger.bus.command.routes")["fallback"] shouldBe literal("async")
            compilation.arguments("messenger.bus.command.send")["transports"] shouldBe
                Argument.Dictionary(listOf(literal("async") to byId("messenger.transport.async")))
        }

        test("a retry on the sync transport is that transport's strategy, with exactly the settings that were made") {
            // Act
            val compilation =
                Compilation("command" to bus("command"), "event" to bus("event")) { container ->
                    container.messenger {
                        // Declared under the name every bus defaults to, so neither bus has to say so.
                        transport<SyncTransport>("sync") {
                            retry<MultiplierRetryStrategy> {
                                maxRetries = 1
                                delay = 2.seconds
                                jitter = 0.0
                            }
                        }

                        bus("command")
                        bus("event")
                    }
                }

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.arguments("messenger.bus.command.transport.sync")["retryStrategy"] shouldBe byId("messenger.transport.sync.retry")
            compilation.arguments("messenger.bus.event.transport.sync")["retryStrategy"] shouldBe byId("messenger.transport.sync.retry")
            compilation.arguments("messenger.bus.event.transport.sync")["messageBus"] shouldBe
                Argument.Deferred(ServiceReference.ById(ServiceId("messenger.bus.event")), Argument.Deferred.Kind.Lazy)
            compilation.arguments("messenger.transport.sync.retry") shouldBe
                mapOf(
                    "maxRetries" to literal(1),
                    "delay" to literal(2.seconds),
                    "jitter" to literal(0.0),
                    "multiplier" to Argument.Default,
                    "maxDelay" to Argument.Default,
                )
        }

        test("a strategy without settings is just named, and a setting that was not made cannot be read back") {
            // Arrange
            var unset: Throwable? = null

            // Act
            val compilation =
                Compilation("bus" to bus("command")) { container ->
                    container.messenger {
                        val sync =
                            transport<SyncTransport>("sync") {
                                retry<MultiplierRetryStrategy> {
                                    multiplier = 3.0
                                    maxDelay = 1.seconds
                                    unset = runCatching { maxRetries }.exceptionOrNull()
                                    multiplier shouldBe 3.0
                                }
                            }

                        bus("command") {
                            routeAll(sync)
                        }
                    }
                }

            // Assert
            compilation.errors.shouldBeEmpty()
            unset?.message shouldBe "\"maxRetries\" was not set here; the strategy's own default applies"
            compilation.arguments("messenger.transport.sync.retry")["multiplier"] shouldBe literal(3.0)
            compilation.arguments("messenger.transport.sync.retry")["maxDelay"] shouldBe literal(1.seconds)
        }

        test("a failure transport is the transport's own, else the global one - and a queue never its own") {
            // Act
            val compilation =
                Compilation(
                    "command" to bus("command"),
                    "event" to bus("event"),
                    "failed" to ServiceKey(typeRef<Transport>(), "failed"),
                ) { container ->
                    container.messenger {
                        val failed = transport<InMemoryTransport>("failed")
                        val other = transport<InMemoryTransport>("other")
                        failureTransport = failed

                        transport<SyncTransport>("sync") {
                            failureTransport = other
                        }
                        val audit = transport<SyncTransport>("audit")

                        bus("command")
                        bus("event") {
                            defaultTransport = audit
                        }
                    }
                }

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.arguments("messenger.bus.command.transport.sync")["failureTransport"] shouldBe byId("messenger.transport.other")
            compilation.arguments("messenger.bus.event.transport.audit")["failureTransport"] shouldBe byId("messenger.transport.failed")
            compilation.entryPoint("failed") shouldBe "messenger.transport.failed"
        }

        test("a sync transport cannot be a failure transport: it would handle the message again instead of keeping it") {
            // Act
            val compilation =
                Compilation("bus" to bus()) { container ->
                    container.messenger {
                        val again = transport<SyncTransport>("again")
                        transport<Queue>("async") {
                            failureTransport = again
                        }
                        bus("command")
                    }
                }

            // Assert
            compilation.compiled.shouldBeNull()
            compilation.errors shouldContainExactly
                listOf(
                    "The transport \"async\" has \"again\" as its failure transport, which is a sync transport: it would handle " +
                        "the failed message again rather than keep it.",
                )
        }
    })
