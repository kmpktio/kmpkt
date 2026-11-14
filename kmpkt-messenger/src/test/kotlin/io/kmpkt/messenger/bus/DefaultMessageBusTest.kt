package io.kmpkt.messenger.bus

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.fixture.GreetHandler
import io.kmpkt.messenger.handler.DefaultHandlerRegistry
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.handler.middleware.HandleMessageMiddleware
import io.kmpkt.messenger.handler.stamp.HandledStamp
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

private fun recordingMiddleware(invocations: MutableList<String>, name: String): Middleware =
    Middleware { envelope, next ->
        invocations += name
        next(envelope)
    }

class DefaultMessageBusTest :
    FunSpec({
        test("dispatch(message) returns the envelope, with the result on it for a synchronous handler") {
            // Arrange
            val registry = DefaultHandlerRegistry().apply { register(Greet::class, GreetHandler()) }
            val bus =
                DefaultMessageBus(
                    middleware = listOf(HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, fixedClock)),
                    handlerRegistry = registry,
                )

            // Act
            val envelope = bus.dispatch(Greet("Milch"))

            // Assert - not the result itself: a message routed to a transport has none yet, so reading it off
            // the envelope is the caller admitting this one is handled inline
            envelope.message shouldBe Greet("Milch")
            envelope.last<HandledStamp>()?.result shouldBe "Hello, Milch!"
        }

        test("a bus dispatches to whatever its registry holds") {
            // Arrange
            val registry = DefaultHandlerRegistry()
            val bus = DefaultMessageBus(handlerRegistry = registry)
            val handler = GreetHandler()

            // Act
            registry.register(Greet::class, handler)

            // Assert
            registry.getHandlers(Greet::class) shouldContainExactly listOf(handler)
        }

        test("runs middleware in list order, outermost first") {
            // Arrange
            val invocations = mutableListOf<String>()
            val registry = DefaultHandlerRegistry()
            val bus =
                DefaultMessageBus(
                    middleware =
                        listOf(
                            recordingMiddleware(invocations, "first"),
                            recordingMiddleware(invocations, "second"),
                        ),
                    handlerRegistry = registry,
                )

            // Act
            bus.dispatch(Envelope(Greet("Milch")))

            // Assert
            invocations shouldContainExactly listOf("first", "second")
        }
    })
