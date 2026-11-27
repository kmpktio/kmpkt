package io.kmpkt.messenger.clikt

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.bus.MessengerCatalog
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.stamp.BusNameStamp
import io.kmpkt.messenger.stamp.ErrorDetailsStamp
import io.kmpkt.messenger.stamp.RedeliveryStamp
import io.kmpkt.messenger.stamp.SentToFailureTransportStamp
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.messenger.transport.Transport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

private data class FailedJob(val name: String) : Message

private fun failureCatalog(failed: Transport = InMemoryTransport()): MessengerCatalog =
    MessengerCatalog(buses = emptyMap(), transports = emptyMap(), failureTransports = mapOf("async" to failed))

class FailedCommandsTest :
    FunSpec({
        test("messenger:failed:show reports empty state when queue has no failed messages") {
            val result = ShowFailedCommand(failureCatalog()).test()

            result.statusCode shouldBe 0
            result.stdout shouldContain "No failed messages found."
        }

        test("messenger:failed:show lists queued failures honoring --max") {
            val failed = InMemoryTransport()
            runBlocking {
                failed.send(
                    Envelope(FailedJob("job-1"))
                        .with(BusNameStamp("commands"))
                        .with(SentToFailureTransportStamp("async"))
                        .with(ErrorDetailsStamp("IllegalStateException", "boom")),
                )
                failed.send(
                    Envelope(FailedJob("job-2"))
                        .with(BusNameStamp("commands"))
                        .with(SentToFailureTransportStamp("async"))
                        .with(ErrorDetailsStamp("IOException", "timeout")),
                )
            }

            val result = ShowFailedCommand(failureCatalog(failed)).test("--max 1")

            result.statusCode shouldBe 0
            result.stdout shouldContain "FailedJob"
            result.stdout shouldContain "IllegalStateException: boom"
        }

        test("messenger:failed:show formats unstamped and message-less failures") {
            val failed = InMemoryTransport()
            runBlocking {
                failed.send(Envelope(FailedJob("unstamped")))
                failed.send(
                    Envelope(FailedJob("null-msg"))
                        .with(SentToFailureTransportStamp("async"))
                        .with(ErrorDetailsStamp("RuntimeException", null)),
                )
            }

            val result = ShowFailedCommand(failureCatalog(failed)).test()

            result.statusCode shouldBe 0
            result.stdout shouldContain "unknown"
            result.stdout shouldContain "RuntimeException: "
            result.stdout shouldNotContain "RuntimeException: null"
        }

        test("messenger:failed:show inspects a failed message by id") {
            val failed = InMemoryTransport()
            runBlocking {
                failed.send(
                    Envelope(FailedJob("job-1"))
                        .with(SentToFailureTransportStamp("async"))
                        .with(ErrorDetailsStamp("IllegalStateException", "boom"))
                        .with(RedeliveryStamp(2)),
                )
                failed.send(
                    Envelope(FailedJob("job-2"))
                        .with(SentToFailureTransportStamp("async"))
                        .with(ErrorDetailsStamp("RuntimeException", null)),
                )
            }
            val items = runBlocking { failed.all().toList() }
            val catalog = failureCatalog(failed)

            val firstDetail = ShowFailedCommand(catalog).test(items.first().id)
            firstDetail.statusCode shouldBe 0
            firstDetail.stdout shouldContain "ID: ${items.first().id}"
            firstDetail.stdout shouldContain "Origin: async"
            firstDetail.stdout shouldContain "Retries: 2"

            val secondDetail = ShowFailedCommand(catalog).test(items.last().id)
            secondDetail.statusCode shouldBe 0
            secondDetail.stdout shouldContain "Error: RuntimeException: "
            secondDetail.stdout shouldNotContain "RuntimeException: null"
        }

        test("messenger:failed:show rejects unknown id and non-positive --max") {
            val catalog = failureCatalog()

            shouldThrow<CliktError> { ShowFailedCommand(catalog).parse(listOf("missing-id")) }
            shouldThrow<CliktError> { ShowFailedCommand(catalog).parse(listOf("--max", "0")) }
        }

        test("messenger:failed:show prints command help") {
            ShowFailedCommand(failureCatalog()).test("--help").stdout shouldContain "Show failed messages"
        }

        test("messenger:failed:retry leaves message in failure queue when handler throws") {
            val failed = InMemoryTransport()
            val failingBus =
                object : MessageBus {
                    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

                    override suspend fun dispatch(envelope: Envelope): Envelope = error("still failing")
                }
            runBlocking {
                failed.send(Envelope(FailedJob("retry-me")).with(BusNameStamp("commands")))
            }
            val id = runBlocking { failed.all().single().id }
            val catalog =
                MessengerCatalog(
                    buses = mapOf("commands" to failingBus),
                    transports = emptyMap(),
                    failureTransports =
                        mapOf(
                            "async" to failed,
                        ),
                )

            shouldThrow<IllegalStateException> {
                RetryFailedCommand(catalog).parse(listOf(id))
            }

            runBlocking { failed.all().toList().size } shouldBe 1
        }

        test("messenger:failed:retry dispatches and removes queued failures with --all") {
            val failed = InMemoryTransport()
            val handled = mutableListOf<String>()
            val bus =
                object : MessageBus {
                    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

                    override suspend fun dispatch(envelope: Envelope): Envelope {
                        handled += (envelope.message as FailedJob).name
                        return envelope
                    }
                }
            runBlocking {
                failed.send(Envelope(FailedJob("retry-1")).with(BusNameStamp("commands")))
                failed.send(Envelope(FailedJob("retry-2")).with(BusNameStamp("commands")))
            }
            val catalog =
                MessengerCatalog(
                    buses = mapOf("commands" to bus),
                    transports = emptyMap(),
                    failureTransports =
                        mapOf(
                            "async" to failed,
                        ),
                )

            val result = RetryFailedCommand(catalog).test("--all")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Retried 2 failed message(s)."
            handled shouldBe listOf("retry-1", "retry-2")
            runBlocking { failed.all().toList() }.shouldBeEmpty()
        }

        test("messenger:failed:retry rejects missing id and conflicting id with --all") {
            val catalog = failureCatalog()

            shouldThrow<CliktError> { RetryFailedCommand(catalog).parse(listOf("missing-id")) }
            shouldThrow<CliktError> { RetryFailedCommand(catalog).parse(listOf("id-1", "--all")) }
        }

        test("messenger:failed:retry prints command help") {
            RetryFailedCommand(failureCatalog()).test("--help").stdout shouldContain "Retry"
        }

        test("messenger:failed:remove deletes specified failed message by id") {
            val failed = InMemoryTransport()
            runBlocking {
                failed.send(Envelope(FailedJob("one")))
                failed.send(Envelope(FailedJob("two")))
            }
            val ids = runBlocking { failed.all().toList().map { it.id } }

            val result = RemoveFailedCommand(failureCatalog(failed)).test(ids.first())

            result.statusCode shouldBe 0
            result.stdout shouldContain "Removed 1 failed message(s)."
            runBlocking { failed.all().toList().map { it.id } } shouldBe listOf(ids.last())
        }

        test("messenger:failed:remove deletes all failed messages with --all") {
            val failed = InMemoryTransport()
            runBlocking {
                failed.send(Envelope(FailedJob("one")))
                failed.send(Envelope(FailedJob("two")))
            }

            val result = RemoveFailedCommand(failureCatalog(failed)).test("--all")

            result.statusCode shouldBe 0
            runBlocking { failed.all().toList() }.shouldBeEmpty()
        }

        test("messenger:failed:remove rejects unknown id and conflicting id with --all") {
            val catalog = failureCatalog()

            shouldThrow<CliktError> { RemoveFailedCommand(catalog).parse(listOf("unknown-id")) }
            shouldThrow<CliktError> { RemoveFailedCommand(catalog).parse(listOf("id-1", "--all")) }
        }

        test("messenger:failed:remove prints command help") {
            RemoveFailedCommand(failureCatalog()).test("--help").stdout shouldContain "Remove"
        }

        test("requires --transport when multiple distinct failure transports exist") {
            val multiCatalog =
                MessengerCatalog(
                    buses = emptyMap(),
                    transports = emptyMap(),
                    failureTransports = mapOf("a" to InMemoryTransport(), "b" to InMemoryTransport()),
                )

            shouldThrow<CliktError> { ShowFailedCommand(multiCatalog).parse(emptyList()) }
            ShowFailedCommand(multiCatalog).test("--transport a").statusCode shouldBe 0
        }

        test("rejects failure transports that do not implement ListableTransport") {
            val nonListable =
                object : Transport {
                    override suspend fun send(envelope: Envelope): Envelope = envelope

                    override suspend fun receive(): List<Envelope> = emptyList()

                    override suspend fun ack(envelope: Envelope) {}

                    override suspend fun reject(envelope: Envelope) {}
                }
            val catalog =
                MessengerCatalog(
                    buses = emptyMap(),
                    transports = emptyMap(),
                    failureTransports = mapOf("a" to nonListable),
                )

            shouldThrow<CliktError> { ShowFailedCommand(catalog).parse(emptyList()) }
        }
    })
