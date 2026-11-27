package io.kmpkt.messenger.transport.middleware

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.messenger.transport.registry.DefaultTransportRegistry
import io.kmpkt.messenger.transport.routing.DefaultTransportRouter
import io.kmpkt.messenger.transport.stamp.RouteStamp
import io.kmpkt.messenger.transport.stamp.SentStamp
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
    routes: Map<KClass<out Message>, String> = emptyMap(),
    transports: Map<String, Transport> = emptyMap(),
) = SendMessageMiddleware(
    transportRouter = DefaultTransportRouter(DefaultTransportRegistry(transports), routes = routes),
    clock = fixedClock,
)

class SendMessageMiddlewareTest :
    FunSpec({
        test("passes an already-received envelope straight through without routing it again") {
            val transport = RecordingTransport()
            val middleware = middleware(mapOf(Greet::class to "greetings"), mapOf("greetings" to transport))
            val received = Envelope(Greet("Milch")).with(ReceivedStamp("greetings"))

            val result = middleware.handle(received, passThrough)

            result shouldBe received
            transport.sent.shouldBeEmpty()
        }

        test("falls through to the handler chain when no route matches") {
            val envelope = Envelope(Greet("Milch"))

            val result = middleware().handle(envelope, passThrough)

            result shouldBe envelope
        }

        test("stamps SentStamp with the transport name before sending to the routed transport") {
            val transport = RecordingTransport()
            val middleware = middleware(mapOf(Greet::class to "greetings"), mapOf("greetings" to transport))

            val result = middleware.handle(Envelope(Greet("Milch")), passThrough)

            val seenByTransport = transport.sent.single()
            seenByTransport.last<SentStamp>()?.transportName shouldBe "greetings"
            result.last<SentStamp>()?.transportName shouldBe "greetings"
        }

        test("returns whatever envelope the transport returned") {
            val handled = Envelope(Greet("Brot"))
            val transport =
                object : Transport {
                    override suspend fun send(envelope: Envelope): Envelope = handled

                    override suspend fun receive(): List<Envelope> = emptyList()

                    override suspend fun ack(envelope: Envelope): Unit = Unit

                    override suspend fun reject(envelope: Envelope): Unit = Unit
                }
            val middleware = middleware(mapOf(Greet::class to "greetings"), mapOf("greetings" to transport))

            middleware.handle(Envelope(Greet("Milch")), passThrough) shouldBe handled
        }

        test("honors RouteStamp override over configured routes") {
            val configured = RecordingTransport()
            val override = RecordingTransport()
            val middleware =
                middleware(
                    mapOf(Greet::class to "greetings"),
                    mapOf("greetings" to configured, "overridden" to override),
                )
            val envelope = Envelope(Greet("Milch")).with(RouteStamp("overridden"))

            middleware.handle(envelope, passThrough)

            override.sent.single().message shouldBe Greet("Milch")
            configured.sent.shouldBeEmpty()
        }

        test("honors RouteStamp(\"sync\") to force inline sync delivery") {
            val configured = RecordingTransport()
            val sync = RecordingTransport()
            val middleware =
                middleware(
                    mapOf(Greet::class to "greetings"),
                    mapOf("greetings" to configured, "sync" to sync),
                )

            middleware.handle(Envelope(Greet("Milch")).with(RouteStamp("sync")), passThrough)

            sync.sent.single().message shouldBe Greet("Milch")
            configured.sent.shouldBeEmpty()
        }

        test("throws when the routed transport name is not registered") {
            val middleware = middleware(mapOf(Greet::class to "greetings"))

            shouldThrow<IllegalStateException> {
                middleware.handle(Envelope(Greet("Milch")), passThrough)
            }
        }
    })
