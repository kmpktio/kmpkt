package io.kmpkt.messenger.logging

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(LoggingMiddlewareTest::class.java)

class LoggingMiddlewareTest :
    FunSpec({
        test("returns whatever the rest of the chain returns") {
            // Arrange
            val middleware = LoggingMiddleware(logger)
            val envelope = Envelope(Greet("Milch"))

            // Act
            val result = middleware.handle(envelope) { it }

            // Assert
            result shouldBe envelope
        }

        test("lets a failure from the rest of the chain propagate") {
            // Arrange
            val middleware = LoggingMiddleware(logger)
            val failure = RuntimeException("boom")

            // Act + Assert
            val thrown =
                shouldThrow<RuntimeException> {
                    middleware.handle(Envelope(Greet("Milch"))) { throw failure }
                }
            thrown shouldBe failure
        }
    })
