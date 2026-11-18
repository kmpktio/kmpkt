package io.kmpkt.messenger.bus.middleware

import io.kmpkt.messenger.bus.DefaultMessageBus
import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.handler.DefaultHandlerRegistry
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.handler.exception.HandlerFailedException
import io.kmpkt.messenger.handler.middleware.HandleMessageMiddleware
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.stamp.DispatchAfterCurrentBusStamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.Collections

private data class OuterCommand(
    val fail: Boolean = false,
    val id: String = "default",
) : Message

private data class DeferredEvent(
    val name: String,
) : Message

class DispatchAfterCurrentBusMiddlewareTest :
    FunSpec({
        test("defers stamped inner dispatches until the outer handler finishes") {
            // Arrange
            val log = mutableListOf<String>()
            lateinit var bus: DefaultMessageBus
            val registry =
                DefaultHandlerRegistry(
                    mapOf(
                        OuterCommand::class to
                            listOf(
                                MessageHandler<OuterCommand, Unit> {
                                    log += "outer:start"
                                    bus.dispatch(Envelope(DeferredEvent("first")).with(DispatchAfterCurrentBusStamp()))
                                    bus.dispatch(Greet("immediate"))
                                    log += "outer:end"
                                },
                            ),
                        DeferredEvent::class to
                            listOf(
                                MessageHandler<DeferredEvent, Unit> { event ->
                                    log += "deferred:${event.name}"
                                    if (event.name == "first") {
                                        bus.dispatch(Envelope(DeferredEvent("chained")).with(DispatchAfterCurrentBusStamp()))
                                    }
                                },
                            ),
                        Greet::class to
                            listOf(
                                MessageHandler<Greet, Unit> { greet ->
                                    log += "immediate:${greet.name}"
                                },
                            ),
                    ),
                )
            bus =
                DefaultMessageBus(
                    middleware =
                        listOf(
                            DispatchAfterCurrentBusMiddleware(),
                            HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC()),
                        ),
                    handlerRegistry = registry,
                )

            // Act
            bus.dispatch(OuterCommand())

            // Assert
            log shouldContainExactly listOf("outer:start", "immediate:immediate", "outer:end", "deferred:first", "deferred:chained")
        }

        test("discards buffered deferred dispatches when the outer handler throws") {
            // Arrange
            val handled = mutableListOf<String>()
            lateinit var bus: DefaultMessageBus
            val registry =
                DefaultHandlerRegistry(
                    mapOf(
                        OuterCommand::class to
                            listOf(
                                MessageHandler<OuterCommand, Unit> { cmd ->
                                    bus.dispatch(Envelope(DeferredEvent("should-not-run")).with(DispatchAfterCurrentBusStamp()))
                                    if (cmd.fail) error("boom")
                                },
                            ),
                        DeferredEvent::class to
                            listOf(
                                MessageHandler<DeferredEvent, Unit> { event ->
                                    handled += event.name
                                },
                            ),
                    ),
                )
            bus =
                DefaultMessageBus(
                    middleware =
                        listOf(
                            DispatchAfterCurrentBusMiddleware(),
                            HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC()),
                        ),
                    handlerRegistry = registry,
                )

            // Act + Assert
            shouldThrow<HandlerFailedException> {
                bus.dispatch(OuterCommand(fail = true))
            }
            handled.shouldBeEmpty()
        }

        test("dispatches immediately when not nested inside an outer bus call") {
            // Arrange
            val handled = mutableListOf<String>()
            val registry =
                DefaultHandlerRegistry(
                    mapOf(
                        DeferredEvent::class to
                            listOf(
                                MessageHandler<DeferredEvent, Unit> { event ->
                                    handled += event.name
                                },
                            ),
                    ),
                )
            val bus =
                DefaultMessageBus(
                    middleware =
                        listOf(
                            DispatchAfterCurrentBusMiddleware(),
                            HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC()),
                        ),
                    handlerRegistry = registry,
                )

            // Act
            bus.dispatch(Envelope(DeferredEvent("top-level")).with(DispatchAfterCurrentBusStamp()))

            // Assert
            handled shouldBe listOf("top-level")
        }

        test("isolates deferred queues across concurrent outer dispatches sharing the same middleware") {
            // Arrange
            val handled = Collections.synchronizedList(mutableListOf<String>())
            val bothStarted = CompletableDeferred<Unit>()
            val okStarted = CompletableDeferred<Unit>()
            val failStarted = CompletableDeferred<Unit>()
            lateinit var bus: DefaultMessageBus
            val registry =
                DefaultHandlerRegistry(
                    mapOf(
                        OuterCommand::class to
                            listOf(
                                MessageHandler<OuterCommand, Unit> { cmd ->
                                    bus.dispatch(Envelope(DeferredEvent(cmd.id)).with(DispatchAfterCurrentBusStamp()))
                                    if (cmd.fail) failStarted.complete(Unit) else okStarted.complete(Unit)
                                    bothStarted.await()
                                    if (cmd.fail) error("boom-${cmd.id}")
                                },
                            ),
                        DeferredEvent::class to
                            listOf(
                                MessageHandler<DeferredEvent, Unit> { event ->
                                    handled += event.name
                                },
                            ),
                    ),
                )
            bus =
                DefaultMessageBus(
                    middleware =
                        listOf(
                            DispatchAfterCurrentBusMiddleware(),
                            HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC()),
                        ),
                    handlerRegistry = registry,
                )

            // Act
            coroutineScope {
                launch(Dispatchers.Default) {
                    okStarted.await()
                    failStarted.await()
                    bothStarted.complete(Unit)
                }
                launch(Dispatchers.Default) {
                    bus.dispatch(OuterCommand(fail = false, id = "ok"))
                }
                launch(Dispatchers.Default) {
                    shouldThrow<HandlerFailedException> {
                        bus.dispatch(OuterCommand(fail = true, id = "fail"))
                    }
                }
            }

            // Assert
            handled shouldContainExactly listOf("ok")
        }

        test("preserves deferred context across dispatcher switches within the outer handler") {
            // Arrange
            val log = mutableListOf<String>()
            lateinit var bus: DefaultMessageBus
            val registry =
                DefaultHandlerRegistry(
                    mapOf(
                        OuterCommand::class to
                            listOf(
                                MessageHandler<OuterCommand, Unit> {
                                    withContext(Dispatchers.Default) {
                                        bus.dispatch(Envelope(DeferredEvent("switched")).with(DispatchAfterCurrentBusStamp()))
                                    }
                                    log += "outer:end"
                                },
                            ),
                        DeferredEvent::class to
                            listOf(
                                MessageHandler<DeferredEvent, Unit> { event ->
                                    log += "deferred:${event.name}"
                                },
                            ),
                    ),
                )
            bus =
                DefaultMessageBus(
                    middleware =
                        listOf(
                            DispatchAfterCurrentBusMiddleware(),
                            HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC()),
                        ),
                    handlerRegistry = registry,
                )

            // Act
            bus.dispatch(OuterCommand())

            // Assert
            log shouldContainExactly listOf("outer:end", "deferred:switched")
        }

        test("safely buffers deferred dispatches from concurrent child coroutines within one outer handler") {
            // Arrange
            val handled = Collections.synchronizedList(mutableListOf<String>())
            lateinit var bus: DefaultMessageBus
            val registry =
                DefaultHandlerRegistry(
                    mapOf(
                        OuterCommand::class to
                            listOf(
                                MessageHandler<OuterCommand, Unit> {
                                    coroutineScope {
                                        repeat(50) { index ->
                                            launch(Dispatchers.Default) {
                                                bus.dispatch(Envelope(DeferredEvent("child-$index")).with(DispatchAfterCurrentBusStamp()))
                                            }
                                        }
                                    }
                                    handled.shouldBeEmpty()
                                },
                            ),
                        DeferredEvent::class to
                            listOf(
                                MessageHandler<DeferredEvent, Unit> { event ->
                                    handled += event.name
                                },
                            ),
                    ),
                )
            bus =
                DefaultMessageBus(
                    middleware =
                        listOf(
                            DispatchAfterCurrentBusMiddleware(),
                            HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC()),
                        ),
                    handlerRegistry = registry,
                )

            // Act
            bus.dispatch(OuterCommand())

            // Assert
            handled shouldContainExactlyInAnyOrder (0 until 50).map { "child-$it" }
        }
    })
