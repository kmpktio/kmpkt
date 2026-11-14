package io.kmpkt.messenger.transport.registry

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Message
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class DefaultTransportRegistryTest :
    FunSpec({
        test("returns the configured transport name for a routed message type") {
            // Arrange
            val registry = DefaultTransportRegistry(mapOf(Greet::class to "greetings"))

            // Act + Assert
            registry.getTransport(Greet::class) shouldBe "greetings"
        }

        test("returns null for a message type with no route") {
            // Arrange
            val registry = DefaultTransportRegistry()

            // Act + Assert
            registry.getTransport(Greet::class).shouldBeNull()
        }

        test("falls back to the catch-all transport for a message type with no route of its own") {
            // Arrange
            val registry = DefaultTransportRegistry(mapOf(Greet::class to "greetings"), fallback = "sync")

            // Act + Assert
            registry.getTransport(Greet::class) shouldBe "greetings"
            registry.getTransport(Message::class) shouldBe "sync"
        }
    })
