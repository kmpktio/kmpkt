package io.kmpkt.messenger.transport.routing

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.messenger.transport.registry.DefaultTransportRegistry
import io.kmpkt.messenger.transport.stamp.RouteStamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private data object Unrouted : Message

private class DummyTransport : Transport {
    override suspend fun send(envelope: Envelope): Envelope = envelope

    override suspend fun receive(): List<Envelope> = emptyList()

    override suspend fun ack(envelope: Envelope): Unit = Unit

    override suspend fun reject(envelope: Envelope): Unit = Unit
}

class DefaultTransportRouterTest :
    FunSpec({
        test("routes a message type to its configured transport") {
            val greetings = DummyTransport()
            val registry = DefaultTransportRegistry(mapOf("greetings" to greetings))
            val router = DefaultTransportRouter(registry, routes = mapOf(Greet::class to "greetings"))

            router.route(Envelope(Greet("Milch"))) shouldBe RoutedTransport("greetings", greetings)
        }

        test("returns null when neither route nor fallback matches") {
            val router = DefaultTransportRouter(DefaultTransportRegistry())

            router.route(Envelope(Greet("Milch"))).shouldBeNull()
        }

        test("falls back to the default transport for unrouted message types") {
            val sync = DummyTransport()
            val greetings = DummyTransport()
            val registry = DefaultTransportRegistry(mapOf("sync" to sync, "greetings" to greetings))
            val router =
                DefaultTransportRouter(
                    transportRegistry = registry,
                    routes = mapOf(Greet::class to "greetings"),
                    fallback = "sync",
                )

            router.route(Envelope(Unrouted)) shouldBe RoutedTransport("sync", sync)
        }

        test("prefers RouteStamp over the configured routing table") {
            val greetings = DummyTransport()
            val override = DummyTransport()
            val registry = DefaultTransportRegistry(mapOf("greetings" to greetings, "override" to override))
            val router = DefaultTransportRouter(registry, routes = mapOf(Greet::class to "greetings"))

            val routed = router.route(Envelope(Greet("Milch")).with(RouteStamp("override")))

            routed shouldBe RoutedTransport("override", override)
        }

        test("fails when a matched route name is absent from the registry") {
            val router = DefaultTransportRouter(DefaultTransportRegistry(), routes = mapOf(Greet::class to "greetings"))

            shouldThrow<IllegalStateException> {
                router.route(Envelope(Greet("Milch")))
            }
        }
    })
