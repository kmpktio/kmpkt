package io.kmpkt.messenger.transport

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.stamp.DelayStamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

private class MovableClock(
    var now: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

class InMemoryTransportTest :
    FunSpec({
        test("hands out what was sent, one at a time and oldest first, and then no more") {
            // Arrange
            val transport = InMemoryTransport()
            val first = transport.send(Envelope(Greet("Milch")))
            val second = transport.send(Envelope(Greet("Brot")))

            // Act + Assert
            transport.pending() shouldBe listOf(first, second)
            transport.receive() shouldBe listOf(first)
            transport.receive() shouldBe listOf(second)
            transport.receive().shouldBeEmpty()
            transport.pending().shouldBeEmpty()
        }

        test("holds a delayed envelope back until its time has come, without holding up the ones behind it") {
            // Arrange
            val clock = MovableClock()
            val transport = InMemoryTransport(clock)
            val delayed = transport.send(Envelope(Greet("Milch")).with(DelayStamp(10.seconds)))
            val immediate = transport.send(Envelope(Greet("Brot")))

            // Act + Assert
            transport.receive() shouldBe listOf(immediate)
            transport.receive().shouldBeEmpty()
            transport.pending() shouldBe listOf(delayed)

            clock.now = clock.now.plusSeconds(10)
            transport.receive() shouldBe listOf(delayed)
        }

        test("records what became of an envelope, and does not bring a rejected one back by itself") {
            // Arrange
            val transport = InMemoryTransport()
            val acked = transport.send(Envelope(Greet("Milch")))
            val rejected = transport.send(Envelope(Greet("Brot")))
            transport.receive()
            transport.receive()

            // Act
            transport.ack(acked)
            transport.reject(rejected)

            // Assert
            transport.acknowledged() shouldBe listOf(acked)
            transport.rejected() shouldBe listOf(rejected)
            transport.receive().shouldBeEmpty()
        }

        test("supports all, find, and remove as a ListableTransport") {
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

            transport.find(all.last().id)?.envelope shouldBe all.last().envelope
            transport.find("missing") shouldBe null

            transport.remove(all.first().id) shouldBe true
            transport.remove(all.first().id) shouldBe false
            transport.all().toList().map { it.id } shouldBe listOf(all.last().id)
        }
    })
