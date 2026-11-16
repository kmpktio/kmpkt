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
        }
    })
