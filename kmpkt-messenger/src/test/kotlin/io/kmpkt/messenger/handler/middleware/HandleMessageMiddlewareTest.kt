package io.kmpkt.messenger.handler.middleware

import io.kmpkt.messenger.fixture.FailingHandler
import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.fixture.GreetHandler
import io.kmpkt.messenger.handler.DefaultHandlerRegistry
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.handler.exception.HandlerFailedException
import io.kmpkt.messenger.handler.exception.NoHandlerForMessageException
import io.kmpkt.messenger.handler.stamp.HandledStamp
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.MessageHandler
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
private val passThrough: suspend (Envelope) -> Envelope = { it }

class HandleMessageMiddlewareTest :
    FunSpec({
        test("invokes the single handler and stamps the result") {
            // Arrange
            val registry = DefaultHandlerRegistry().apply { register(Greet::class, GreetHandler()) }
            val middleware = HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, fixedClock)

            // Act
            val result = middleware.handle(Envelope(Greet("Milch")), passThrough)

            // Assert
            result.last<HandledStamp>()?.result shouldBe "Hello, Milch!"
        }

        test("SINGLE policy throws when no handler is registered") {
            // Arrange
            val middleware = HandleMessageMiddleware(DefaultHandlerRegistry(), HandlerPolicy.SINGLE, fixedClock)

            // Act + Assert
            shouldThrow<NoHandlerForMessageException> {
                middleware.handle(Envelope(Greet("Milch")), passThrough)
            }
        }

        test("SINGLE policy throws when more than one handler is registered") {
            // Arrange
            val registry =
                DefaultHandlerRegistry().apply {
                    register(Greet::class, GreetHandler())
                    register(Greet::class, GreetHandler())
                }
            val middleware = HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, fixedClock)

            // Act + Assert
            shouldThrow<IllegalStateException> {
                middleware.handle(Envelope(Greet("Milch")), passThrough)
            }
        }

        test("MULTIPLE policy allows zero handlers") {
            // Arrange
            val middleware = HandleMessageMiddleware(DefaultHandlerRegistry(), HandlerPolicy.MULTIPLE, fixedClock)

            // Act
            val result = middleware.handle(Envelope(Greet("Milch")), passThrough)

            // Assert
            result.all<HandledStamp>().shouldBeEmpty()
        }

        test("MULTIPLE policy invokes every registered handler") {
            // Arrange
            val first = GreetHandler()
            val second = GreetHandler()
            val registry =
                DefaultHandlerRegistry().apply {
                    register(Greet::class, first)
                    register(Greet::class, second)
                }
            val middleware = HandleMessageMiddleware(registry, HandlerPolicy.MULTIPLE, fixedClock)

            // Act
            middleware.handle(Envelope(Greet("Milch")), passThrough)

            // Assert
            first.invocations shouldBe listOf(Greet("Milch"))
            second.invocations shouldBe listOf(Greet("Milch"))
        }

        test("does not re-invoke a handler whose HandledStamp is already on the envelope") {
            // Arrange
            val handler = GreetHandler()
            val registry = DefaultHandlerRegistry().apply { register(Greet::class, handler) }
            val middleware = HandleMessageMiddleware(registry, HandlerPolicy.MULTIPLE, fixedClock)

            @Suppress("UNCHECKED_CAST")
            val handlerClass = handler::class as KClass<MessageHandler<*, *>>
            val handledStamp = HandledStamp(result = "Hello, Milch!", handlerClass = handlerClass, handledAt = fixedClock.instant())
            val alreadyHandled = Envelope(Greet("Milch")).with(handledStamp)

            // Act
            middleware.handle(alreadyHandled, passThrough)

            // Assert
            handler.invocations.shouldBeEmpty()
        }

        test("wraps every handler failure into HandlerFailedException, first failure as cause") {
            // Arrange
            val first = RuntimeException("first")
            val second = RuntimeException("second")
            val registry =
                DefaultHandlerRegistry().apply {
                    register(Greet::class, FailingHandler(first))
                    register(Greet::class, FailingHandler(second))
                }
            val middleware = HandleMessageMiddleware(registry, HandlerPolicy.MULTIPLE, fixedClock)

            // Act + Assert
            val exception =
                shouldThrow<HandlerFailedException> {
                    middleware.handle(Envelope(Greet("Milch")), passThrough)
                }
            exception.cause shouldBe first
        }
    })
