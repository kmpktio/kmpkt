package io.kmpkt.messenger.persistence

import io.kmpkt.messenger.bus.DefaultMessageBus
import io.kmpkt.messenger.handler.DefaultHandlerRegistry
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.handler.exception.HandlerFailedException
import io.kmpkt.messenger.handler.middleware.HandleMessageMiddleware
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.persistence.middleware.TransactionMiddleware
import io.kmpkt.messenger.persistence.serialization.EnvelopeSerializer
import io.kmpkt.messenger.persistence.transport.PersistentTransport
import io.kmpkt.messenger.stamp.DelayStamp
import io.kmpkt.persistence.InMemoryPersistence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

private data class TestJob(
    val name: String,
    val shouldFail: Boolean = false,
) : Message

private class MovableClock(
    var now: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

private class TestEnvelopeSerializer : EnvelopeSerializer {
    override fun serialize(envelope: Envelope): String {
        val job = envelope.message as TestJob
        return "${job.name}|${job.shouldFail}"
    }

    override fun deserialize(payload: String): Envelope {
        val parts = payload.split('|', limit = 2)
        return Envelope(TestJob(parts[0], parts[1].toBoolean()))
    }
}

class PersistentTransportTest :
    FunSpec({
        test("send, receive, ack, and DelayStamp work over WorkQueue") {
            val clock = MovableClock()
            val persistence = InMemoryPersistence()
            val transport = PersistentTransport(persistence, "jobs", TestEnvelopeSerializer(), clock)

            transport.send(Envelope(TestJob("immediate")))
            transport.send(Envelope(TestJob("delayed")).with(DelayStamp(30.seconds)))

            val first = transport.receive()
            first shouldHaveSize 1
            (first.first().message as TestJob).name shouldBe "immediate"
            transport.ack(first.first())

            transport.receive().shouldBeEmpty()

            clock.now = clock.now.plusSeconds(30)
            val second = transport.receive()
            second shouldHaveSize 1
            (second.first().message as TestJob).name shouldBe "delayed"
            transport.ack(second.first())

            transport.receive().shouldBeEmpty()
        }

        test("reject marks the queue item as failed so it is not claimed again") {
            val clock = MovableClock()
            val persistence = InMemoryPersistence()
            val transport = PersistentTransport(persistence, "jobs", TestEnvelopeSerializer(), clock)

            transport.send(Envelope(TestJob("poison")))
            val claimed = transport.receive()
            claimed shouldHaveSize 1

            transport.reject(claimed.first())
            transport.receive().shouldBeEmpty()
        }

        test("TransactionMiddleware commits on success and rolls back all persistence writes on failure") {
            val persistence = InMemoryPersistence()
            val store = persistence.keyValueStore("state")
            val registry =
                DefaultHandlerRegistry().apply {
                    register(
                        TestJob::class,
                        object : MessageHandler<TestJob, Unit> {
                            override suspend fun invoke(message: TestJob) {
                                store.put("last", message.name)
                                if (message.shouldFail) error("boom")
                            }
                        },
                    )
                }
            val bus =
                DefaultMessageBus(
                    middleware =
                        listOf(
                            TransactionMiddleware(persistence),
                            HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC()),
                        ),
                    handlerRegistry = registry,
                )

            bus.dispatch(TestJob("ok"))
            store.get("last") shouldBe "ok"

            shouldThrow<HandlerFailedException> {
                bus.dispatch(TestJob("broken", shouldFail = true))
            }
            store.get("last") shouldBe "ok"

            val freshStore = InMemoryPersistence().keyValueStore("state")
            freshStore.get("last").shouldBeNull()
        }
    })
