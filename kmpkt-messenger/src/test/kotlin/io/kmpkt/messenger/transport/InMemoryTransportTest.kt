package io.kmpkt.messenger.transport

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.stamp.DelayStamp
import io.kmpkt.test.TestClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration.Companion.seconds

class InMemoryTransportTest :
    FunSpec({
        test("hands out what was sent, one at a time and oldest first, and then no more") {
            val transport = InMemoryTransport()
            val first = transport.send(Envelope(Greet("Milch")))
            val second = transport.send(Envelope(Greet("Brot")))

            transport.pending() shouldBe listOf(first, second)
            transport.receive() shouldBe listOf(first)
            transport.receive() shouldBe listOf(second)
            transport.receive().shouldBeEmpty()
            transport.pending().shouldBeEmpty()
        }

        test("holds a delayed envelope back until its time has come, without holding up the ones behind it") {
            val clock = TestClock()
            val transport = InMemoryTransport(clock)
            val delayed = transport.send(Envelope(Greet("Milch")).with(DelayStamp(10.seconds)))
            val immediate = transport.send(Envelope(Greet("Brot")))

            transport.receive() shouldBe listOf(immediate)
            transport.receive().shouldBeEmpty()
            transport.pending() shouldBe listOf(delayed)

            clock.advance(10.seconds)
            transport.receive() shouldBe listOf(delayed)
        }

        test("records what became of an envelope, and does not bring a rejected one back by itself") {
            val transport = InMemoryTransport()
            val acked = transport.send(Envelope(Greet("Milch")))
            val rejected = transport.send(Envelope(Greet("Brot")))
            transport.receive()
            transport.receive()

            transport.ack(acked)
            transport.reject(rejected)

            transport.acknowledged() shouldBe listOf(acked)
            transport.rejected() shouldBe listOf(rejected)
            transport.receive().shouldBeEmpty()
        }

        test("streams queued envelopes via ListableTransport.all") {
            val transport = InMemoryTransport()
            transport.send(Envelope(Greet("first")))
            transport.send(Envelope(Greet("second")))

            val all = transport.all().toList()
            all.map { (it.envelope.message as Greet).name } shouldBe listOf("first", "second")
            transport
                .all()
                .take(1)
                .toList()
                .map { it.id } shouldBe listOf(all.first().id)
        }

        test("looks up queued envelopes by id via ListableTransport.find") {
            val transport = InMemoryTransport()
            transport.send(Envelope(Greet("first")))
            val queued = transport.all().toList().single()

            transport.find(queued.id)?.envelope shouldBe queued.envelope
            transport.find("missing").shouldBeNull()
        }

        test("removes queued envelopes by id via ListableTransport.remove") {
            val transport = InMemoryTransport()
            transport.send(Envelope(Greet("first")))
            transport.send(Envelope(Greet("second")))
            val all = transport.all().toList()

            transport.remove(all.first().id) shouldBe true
            transport.remove(all.first().id) shouldBe false
            transport.all().toList().map { it.id } shouldBe listOf(all.last().id)
        }
    })
