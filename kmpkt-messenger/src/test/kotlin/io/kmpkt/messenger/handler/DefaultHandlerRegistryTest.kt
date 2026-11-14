package io.kmpkt.messenger.handler

import io.kmpkt.messenger.fixture.FailingHandler
import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.fixture.GreetHandler
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.reflect.KClass

class DefaultHandlerRegistryTest :
    FunSpec({
        test("returns no handlers for an unregistered message type") {
            // Arrange
            val registry = DefaultHandlerRegistry()

            // Act + Assert
            registry.getHandlers(Greet::class).shouldBeEmpty()
        }

        test("returns a registered handler") {
            // Arrange
            val registry = DefaultHandlerRegistry()
            val handler = GreetHandler()

            // Act
            registry.register(Greet::class, handler)

            // Assert
            registry.getHandlers(Greet::class) shouldContainExactly listOf(handler)
        }

        test("accumulates multiple handlers for the same message type") {
            // Arrange
            val registry = DefaultHandlerRegistry()
            val first = GreetHandler()
            val second = FailingHandler(RuntimeException())

            // Act
            registry.register(Greet::class, first)
            registry.register(Greet::class, second)

            // Assert
            registry.getHandlers(Greet::class) shouldContainExactly listOf(first, second)
        }

        test("starts out with the handlers it was constructed with, and takes further ones without touching the map given") {
            // Arrange
            val first = GreetHandler()
            val second = GreetHandler()
            val given: Map<KClass<out Message>, List<MessageHandler<*, *>>> = mapOf(Greet::class to listOf(first))
            val registry = DefaultHandlerRegistry(given)

            // Act
            registry.register(Greet::class, second)

            // Assert
            registry.getHandlers(Greet::class) shouldContainExactly listOf(first, second)
            given.getValue(Greet::class) shouldContainExactly listOf(first)
        }
    })
