package io.kmpkt.messenger.persistence

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.persistence.serialization.EnvelopeSerializer
import io.kmpkt.messenger.persistence.stamp.PersistentQueueItemStamp
import io.kmpkt.messenger.persistence.transport.PersistentTransport
import io.kmpkt.messenger.stamp.DelayStamp
import io.kmpkt.persistence.InMemoryPersistence
import io.kmpkt.test.TestClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration.Companion.seconds

private data class QueuedJob(
    val name: String,
) : Message

private object QueuedJobSerializer : EnvelopeSerializer {
    override fun serialize(envelope: Envelope): String = (envelope.message as QueuedJob).name

    override fun deserialize(payload: String): Envelope = Envelope(QueuedJob(payload))
}

class PersistentTransportTest :
    FunSpec({
        test("delivers immediate envelopes and holds delayed envelopes until DelayStamp expires") {
            val clock = TestClock()
            val persistence = InMemoryPersistence()
            val transport = PersistentTransport(persistence, "jobs", QueuedJobSerializer, clock)

            transport.send(Envelope(QueuedJob("immediate")))
            transport.send(Envelope(QueuedJob("delayed")).with(DelayStamp(30.seconds)))

            val first = transport.receive()
            first shouldHaveSize 1
            (first.first().message as QueuedJob).name shouldBe "immediate"
            val claimedId = first.first().last<PersistentQueueItemStamp>()!!.itemId
            transport.ack(first.first())
            persistence.workQueue("jobs").remove(claimedId) shouldBe false

            transport.receive().shouldBeEmpty()

            clock.advance(30.seconds)
            val second = transport.receive()
            second shouldHaveSize 1
            (second.first().message as QueuedJob).name shouldBe "delayed"
            transport.ack(second.first())

            transport.receive().shouldBeEmpty()
        }

        test("marks rejected queue items as failed so they are not claimed again") {
            val clock = TestClock()
            val persistence = InMemoryPersistence()
            val transport = PersistentTransport(persistence, "jobs", QueuedJobSerializer, clock)

            transport.send(Envelope(QueuedJob("poison")))
            val claimed = transport.receive()
            claimed shouldHaveSize 1

            transport.reject(claimed.first())

            transport.receive().shouldBeEmpty()
        }

        test("sets up backing queue without error") {
            val transport = PersistentTransport(InMemoryPersistence(), "jobs", QueuedJobSerializer, TestClock())

            transport.setup()
        }

        test("streams queued envelopes in order via ListableTransport.all") {
            val clock = TestClock()
            val transport = PersistentTransport(InMemoryPersistence(), "jobs", QueuedJobSerializer, clock)

            transport.send(Envelope(QueuedJob("one")))
            clock.advance(1.seconds)
            transport.send(Envelope(QueuedJob("two")))

            val listed = transport.all().toList()
            listed.map { (it.envelope.message as QueuedJob).name } shouldBe listOf("one", "two")
        }

        test("looks up queued envelopes by id via ListableTransport.find") {
            val transport = PersistentTransport(InMemoryPersistence(), "jobs", QueuedJobSerializer, TestClock())
            transport.send(Envelope(QueuedJob("one")))
            val id =
                transport
                    .all()
                    .toList()
                    .single()
                    .id

            (transport.find(id)?.envelope?.message as? QueuedJob)?.name shouldBe "one"
            transport.find("missing").shouldBeNull()
        }

        test("removes queued envelopes by id via ListableTransport.remove") {
            val clock = TestClock()
            val transport = PersistentTransport(InMemoryPersistence(), "jobs", QueuedJobSerializer, clock)
            transport.send(Envelope(QueuedJob("one")))
            clock.advance(1.seconds)
            transport.send(Envelope(QueuedJob("two")))
            val firstId =
                transport
                    .all()
                    .toList()
                    .first()
                    .id

            transport.remove(firstId) shouldBe true
            transport.remove("missing") shouldBe false
            transport.all().toList() shouldHaveSize 1
        }

        test("rejects ack when PersistentQueueItemStamp is missing") {
            val transport = PersistentTransport(InMemoryPersistence(), "jobs", QueuedJobSerializer, TestClock())

            val error =
                shouldThrow<IllegalStateException> {
                    transport.ack(Envelope(QueuedJob("untracked")))
                }

            error.message shouldContain "PersistentQueueItemStamp"
        }

        test("rejects reject when PersistentQueueItemStamp is missing") {
            val transport = PersistentTransport(InMemoryPersistence(), "jobs", QueuedJobSerializer, TestClock())

            val error =
                shouldThrow<IllegalStateException> {
                    transport.reject(Envelope(QueuedJob("untracked")))
                }

            error.message shouldContain "PersistentQueueItemStamp"
        }
    })
