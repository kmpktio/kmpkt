package io.kmpkt.messenger.clikt

import com.github.ajalt.clikt.testing.test
import io.kmpkt.messenger.bus.MessengerCatalog
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.messenger.transport.SetupableTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

private class RecordingSetupableTransport : SetupableTransport {
    var setupCalls: Int = 0

    override suspend fun setup() {
        setupCalls += 1
    }

    override suspend fun send(envelope: Envelope): Envelope = envelope

    override suspend fun receive(): List<Envelope> = emptyList()

    override suspend fun ack(envelope: Envelope) {}

    override suspend fun reject(envelope: Envelope) {}
}

class SetupTransportsCommandTest :
    FunSpec({
        test("sets up all registered SetupableTransports when no arguments are given") {
            val setupable = RecordingSetupableTransport()
            val plain = InMemoryTransport()
            val catalog =
                MessengerCatalog(
                    buses = emptyMap(),
                    transports = mapOf("async" to setupable, "mem" to plain),
                )

            val result = SetupTransportsCommand(catalog).test()

            result.statusCode shouldBe 0
            setupable.setupCalls shouldBe 1
            result.stdout shouldContain "Set up transport \"async\"."
            result.stdout shouldContain "Transport \"mem\" does not require setup."
        }

        test("sets up only the named transports when arguments are given") {
            val first = RecordingSetupableTransport()
            val second = RecordingSetupableTransport()
            val catalog =
                MessengerCatalog(
                    buses = emptyMap(),
                    transports = mapOf("first" to first, "second" to second),
                )

            val result = SetupTransportsCommand(catalog).test("second")

            result.statusCode shouldBe 0
            first.setupCalls shouldBe 0
            second.setupCalls shouldBe 1
        }

        test("fails with UsageError for unknown transport names") {
            val catalog =
                MessengerCatalog(
                    buses = emptyMap(),
                    transports = mapOf("async" to RecordingSetupableTransport()),
                )

            val result = SetupTransportsCommand(catalog).test("missing")

            result.statusCode shouldNotBe 0
            result.stderr shouldContain "Unknown transport(s): missing"
        }
    })
