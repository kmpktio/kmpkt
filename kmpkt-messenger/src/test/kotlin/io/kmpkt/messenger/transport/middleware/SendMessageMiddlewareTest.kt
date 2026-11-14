package io.kmpkt.messenger.transport.middleware

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.messenger.transport.registry.DefaultTransportRegistry
import io.kmpkt.messenger.transport.stamp.RouteStamp
import io.kmpkt.messenger.transport.stamp.SentStamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
private val passThrough: suspend (Envelope) -> Envelope = { it }

private class RecordingTransport : Transport {
    val sent = mutableListOf<Envelope>()

    override suspend fun send(envelope: Envelope): Envelope {
        sent += envelope
        return envelope
    }

    override suspend fun receive(): List<Envelope> = emptyList()

    override suspend fun ack(envelope: Envelope): Unit = Unit

    override suspend fun reject(envelope: Envelope): Unit = Unit
}

private fun middleware(
    routes: Map<kotlin.reflect.KClass<out io.kmpkt.messenger.message.Message>, String> = emptyMap(),
    transports: Map<String, Transport> = emptyMap(),
) = SendMessageMiddleware(DefaultTransportRegistry(routes), transports, fixedClock)

class SendMessageMiddlewareTest :
    FunSpec({
        test("passes an already-received envelope straight through, without consulting the registry") {
            // Arrange
            val transport = RecordingTransport()
            val middleware = middleware(mapOf(Greet::class to "greetings"), mapOf("greetings" to transport))
            val received = Envelope(Greet("Milch")).with(ReceivedStamp("greetings"))

            // Act
            val result = middleware.handle(received, passThrough)

            // Assert
            result shouldBe received
            transport.sent.shouldBeEmpty()
        }

        test("falls through to the handler when no route is configured") {
            // Arrange
            val envelope = Envelope(Greet("Milch"))

            // Act
            val result = middleware().handle(envelope, passThrough)

            // Assert
            result shouldBe envelope
        }

        test("routes to the transport configured in the registry, stamped before the transport sees it") {
            // Arrange
            val transport = RecordingTransport()
            val middleware = middleware(mapOf(Greet::class to "greetings"), mapOf("greetings" to transport))

            // Act
            val result = middleware.handle(Envelope(Greet("Milch")), passThrough)

            // Assert
            val seenByTransport = transport.sent.single()
            seenByTransport.last<SentStamp>()?.transportName shouldBe "greetings"
            result.last<SentStamp>()?.transportName shouldBe "greetings"
        }

        test("passes back whatever the transport returned, so a handler's result survives") {
            // Arrange
            val handled = Envelope(Greet("Brot"))
            val transport =
                object : Transport {
                    override suspend fun send(envelope: Envelope): Envelope = handled

                    override suspend fun receive(): List<Envelope> = emptyList()

                    override suspend fun ack(envelope: Envelope): Unit = Unit

                    override suspend fun reject(envelope: Envelope): Unit = Unit
                }
            val middleware = middleware(mapOf(Greet::class to "greetings"), mapOf("greetings" to transport))

            // Act + Assert
            middleware.handle(Envelope(Greet("Milch")), passThrough) shouldBe handled
        }

        test("a RouteStamp overrides the registry") {
            // Arrange
            val configured = RecordingTransport()
            val override = RecordingTransport()
            val middleware =
                middleware(
                    mapOf(Greet::class to "greetings"),
                    mapOf("greetings" to configured, "overridden" to override),
                )
            val envelope = Envelope(Greet("Milch")).with(RouteStamp("overridden"))

            // Act
            middleware.handle(envelope, passThrough)

            // Assert
            override.sent.single().message shouldBe Greet("Milch")
            configured.sent.shouldBeEmpty()
        }

        test("a RouteStamp naming the sync transport overrides a message's configured route") {
            // Arrange
            val configured = RecordingTransport()
            val sync = RecordingTransport()
            val middleware =
                middleware(
                    mapOf(Greet::class to "greetings"),
                    mapOf("greetings" to configured, "sync" to sync),
                )

            // Act
            middleware.handle(Envelope(Greet("Milch")).with(RouteStamp("sync")), passThrough)

            // Assert
            sync.sent.single().message shouldBe Greet("Milch")
            configured.sent.shouldBeEmpty()
        }

        test("throws when the configured transport name is not registered") {
            // Arrange
            val middleware = middleware(mapOf(Greet::class to "greetings"))

            // Act + Assert
            shouldThrow<IllegalStateException> {
                middleware.handle(Envelope(Greet("Milch")), passThrough)
            }
        }
    })
