package io.kmpkt.messenger.transport.registry

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.transport.Transport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private object StubTransport : Transport {
    override suspend fun send(envelope: Envelope): Envelope = envelope

    override suspend fun receive(): List<Envelope> = emptyList()

    override suspend fun ack(envelope: Envelope): Unit = Unit

    override suspend fun reject(envelope: Envelope): Unit = Unit
}

class DefaultTransportRegistryTest :
    FunSpec({
        test("resolves a registered transport by name") {
            val registry = DefaultTransportRegistry(mapOf("sync" to StubTransport, "async" to StubTransport))

            registry.get("async") shouldBe StubTransport
        }

        test("exposes all registered transports keyed by name") {
            val registry = DefaultTransportRegistry(mapOf("sync" to StubTransport, "async" to StubTransport))

            registry.all() shouldBe mapOf("sync" to StubTransport, "async" to StubTransport)
        }

        test("lists registered names when looking up an unknown transport") {
            val registry = DefaultTransportRegistry(mapOf("sync" to StubTransport, "async" to StubTransport))

            val failure =
                shouldThrow<IllegalStateException> {
                    registry.get("missing")
                }

            failure.message shouldContain "Transport 'missing' not found - registered: [async, sync]"
        }
    })
