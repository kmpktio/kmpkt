package io.kmpkt.messenger.clikt

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.bus.MessengerCatalog
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.stamp.BusNameStamp
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.worker.WorkLock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private data class TestJob(val name: String) : Message

private class RecordingBus : MessageBus {
    val handled = mutableListOf<Envelope>()

    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

    override suspend fun dispatch(envelope: Envelope): Envelope {
        handled += envelope
        return envelope
    }
}

class ConsumeCommandTest :
    FunSpec({
        test("consumes prioritized receivers in order and routes via BusNameStamp") {
            val high = InMemoryTransport()
            val low = InMemoryTransport()
            val commands = RecordingBus()
            val events = RecordingBus()

            runBlocking {
                high.send(Envelope(TestJob("urgent")).with(BusNameStamp("commands")))
                low.send(Envelope(TestJob("normal")).with(BusNameStamp("events")))
            }

            val catalog =
                MessengerCatalog(
                    buses = mapOf("commands" to commands, "events" to events),
                    transports = linkedMapOf("high" to high, "low" to low),
                )

            ConsumeCommand(catalog).parse(listOf("high", "low", "--limit", "2", "--sleep", "1"))

            commands.handled.map { (it.message as TestJob).name } shouldBe listOf("urgent")
            events.handled.map { (it.message as TestJob).name } shouldBe listOf("normal")
        }

        test("supports --bus override and --all") {
            val queue = InMemoryTransport()
            val commands = RecordingBus()
            val events = RecordingBus()

            runBlocking {
                queue.send(Envelope(TestJob("forced")).with(BusNameStamp("commands")))
            }

            val catalog =
                MessengerCatalog(
                    buses = mapOf("commands" to commands, "events" to events),
                    transports = mapOf("queue" to queue),
                )

            ConsumeCommand(catalog).parse(listOf("--all", "--bus", "events", "--limit", "1", "--sleep", "1"))

            commands.handled shouldBe emptyList()
            events.handled.map { (it.message as TestJob).name } shouldBe listOf("forced")
        }

        test("acquires WorkLock when --lock is specified and fails when no WorkLock is available") {
            val queue = InMemoryTransport()
            val bus = RecordingBus()
            runBlocking { queue.send(Envelope(TestJob("locked")).with(BusNameStamp("default"))) }

            val acquiredLocks = mutableListOf<String>()
            val lock =
                object : WorkLock {
                    override suspend fun <R> underLock(
                        name: String,
                        block: suspend () -> R,
                    ): R {
                        acquiredLocks += name
                        return block()
                    }
                }
            val catalog = MessengerCatalog(buses = mapOf("default" to bus), transports = mapOf("queue" to queue))

            ConsumeCommand(catalog, workLock = lock).parse(listOf("queue", "--lock", "worker-lock", "--limit", "1", "--sleep", "1"))
            acquiredLocks shouldBe listOf("worker-lock")

            shouldThrow<CliktError> {
                ConsumeCommand(catalog, workLock = null).parse(listOf("queue", "--lock", "worker-lock", "--limit", "1"))
            }
        }

        test("fails on missing receiver or unknown transport") {
            val catalog = MessengerCatalog(buses = emptyMap(), transports = mapOf("queue" to InMemoryTransport()))

            shouldThrow<CliktError> {
                ConsumeCommand(catalog).parse(emptyList())
            }
            shouldThrow<CliktError> {
                ConsumeCommand(catalog).parse(listOf("unknown"))
            }
            shouldThrow<CliktError> {
                ConsumeCommand(catalog).parse(listOf("queue", "--all"))
            }
        }

        test("stops after --failure-limit failed messages") {
            val queue = InMemoryTransport()
            val bus =
                object : MessageBus {
                    val seen = mutableListOf<String>()

                    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

                    override suspend fun dispatch(envelope: Envelope): Envelope {
                        val name = (envelope.message as TestJob).name
                        seen += name
                        if (name.startsWith("fail")) error("boom")
                        return envelope
                    }
                }

            runBlocking {
                queue.send(Envelope(TestJob("fail-1")).with(BusNameStamp("default")))
                queue.send(Envelope(TestJob("ok-after-1")).with(BusNameStamp("default")))
                queue.send(Envelope(TestJob("fail-2")).with(BusNameStamp("default")))
                queue.send(Envelope(TestJob("never-reached")).with(BusNameStamp("default")))
            }

            val catalog = MessengerCatalog(buses = mapOf("default" to bus), transports = mapOf("queue" to queue))

            ConsumeCommand(catalog).parse(listOf("queue", "--failure-limit", "2", "--sleep", "1"))

            bus.seen shouldBe listOf("fail-1", "ok-after-1", "fail-2")
        }

        test("stops once --time-limit deadline is reached") {
            val queue = InMemoryTransport()
            var now = Instant.parse("2026-01-01T00:00:00Z")
            val mutableClock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = now
                }
            val bus =
                object : MessageBus {
                    val seen = mutableListOf<String>()

                    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

                    override suspend fun dispatch(envelope: Envelope): Envelope {
                        seen += (envelope.message as TestJob).name
                        now = now.plusSeconds(5)
                        return envelope
                    }
                }

            runBlocking {
                queue.send(Envelope(TestJob("first")).with(BusNameStamp("default")))
                queue.send(Envelope(TestJob("second")).with(BusNameStamp("default")))
            }

            val catalog = MessengerCatalog(buses = mapOf("default" to bus), transports = mapOf("queue" to queue))

            ConsumeCommand(catalog, clock = mutableClock).parse(listOf("queue", "--time-limit", "5", "--sleep", "1"))

            bus.seen shouldBe listOf("first")
        }

        test("rejects non-positive limits") {
            val catalog = MessengerCatalog(buses = emptyMap(), transports = mapOf("queue" to InMemoryTransport()))

            shouldThrow<CliktError> { ConsumeCommand(catalog).parse(listOf("queue", "--limit", "0")) }
            shouldThrow<CliktError> { ConsumeCommand(catalog).parse(listOf("queue", "--time-limit", "0")) }
            shouldThrow<CliktError> { ConsumeCommand(catalog).parse(listOf("queue", "--failure-limit", "-1")) }
            shouldThrow<CliktError> { ConsumeCommand(catalog).parse(listOf("queue", "--sleep", "-1")) }
        }
    })
