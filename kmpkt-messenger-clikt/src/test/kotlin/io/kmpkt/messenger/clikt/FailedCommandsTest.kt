package io.kmpkt.messenger.clikt

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.bus.MessengerCatalog
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.stamp.BusNameStamp
import io.kmpkt.messenger.stamp.RedeliveryStamp
import io.kmpkt.messenger.stamp.SentToFailureTransportStamp
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.messenger.transport.Transport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

private data class FailedJob(val name: String) : Message

class FailedCommandsTest :
    FunSpec({
        test("messenger:failed:show lists queued failures and inspects by id") {
            val failed = InMemoryTransport()
            runBlocking {
                failed.send(
                    Envelope(FailedJob("job-1"))
                        .with(BusNameStamp("commands"))
                        .with(SentToFailureTransportStamp("async", "IllegalStateException", "boom"))
                        .with(RedeliveryStamp(2)),
                )
                failed.send(
                    Envelope(FailedJob("job-2"))
                        .with(BusNameStamp("commands"))
                        .with(SentToFailureTransportStamp("async", "IOException", "timeout")),
                )
            }

            val catalog = MessengerCatalog(buses = emptyMap(), transports = emptyMap(), failureTransports = mapOf("async" to failed))

            val listResult = ShowFailedCommand(catalog).test("--max 1")
            listResult.statusCode shouldBe 0
            listResult.stdout shouldContain "FailedJob"
            listResult.stdout shouldContain "IllegalStateException: boom"

            val firstId = runBlocking { failed.all().first().id }
            val detailResult = ShowFailedCommand(catalog).test(firstId)
            detailResult.statusCode shouldBe 0
            detailResult.stdout shouldContain "ID: $firstId"
            detailResult.stdout shouldContain "Origin: async"
            detailResult.stdout shouldContain "Retries: 2"

            shouldThrow<CliktError> { ShowFailedCommand(catalog).parse(listOf("missing-id")) }
            shouldThrow<CliktError> { ShowFailedCommand(catalog).parse(listOf("--max", "0")) }
        }

        test("messenger:failed:retry dispatches failed messages and removes them only on success") {
            val failed = InMemoryTransport()
            val handled = mutableListOf<String>()
            var shouldFail = true
            val bus =
                object : MessageBus {
                    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

                    override suspend fun dispatch(envelope: Envelope): Envelope {
                        if (shouldFail) error("still failing")
                        handled += (envelope.message as FailedJob).name
                        return envelope
                    }
                }

            runBlocking {
                failed.send(Envelope(FailedJob("retry-me")).with(BusNameStamp("commands")))
            }
            val id = runBlocking { failed.all().single().id }
            val catalog =
                MessengerCatalog(
                    buses = mapOf("commands" to bus),
                    transports = emptyMap(),
                    failureTransports = mapOf("async" to failed),
                )

            shouldThrow<IllegalStateException> {
                RetryFailedCommand(catalog).parse(listOf(id))
            }
            runBlocking { failed.all().toList().size } shouldBe 1

            shouldFail = false
            val retryResult = RetryFailedCommand(catalog).test("--all")
            retryResult.statusCode shouldBe 0
            retryResult.stdout shouldContain "Retried 1 failed message(s)."
            handled shouldBe listOf("retry-me")
            runBlocking { failed.all().toList() }.shouldBeEmpty()
        }

        test("messenger:failed:remove deletes specified or all failed messages") {
            val failed = InMemoryTransport()
            runBlocking {
                failed.send(Envelope(FailedJob("one")))
                failed.send(Envelope(FailedJob("two")))
            }
            val ids = runBlocking { failed.all().toList().map { it.id } }
            val catalog = MessengerCatalog(buses = emptyMap(), transports = emptyMap(), failureTransports = mapOf("async" to failed))

            val removeOne = RemoveFailedCommand(catalog).test(ids.first())
            removeOne.statusCode shouldBe 0
            removeOne.stdout shouldContain "Removed 1 failed message(s)."
            runBlocking { failed.all().toList().map { it.id } } shouldBe listOf(ids.last())

            val removeRest = RemoveFailedCommand(catalog).test("--all")
            removeRest.statusCode shouldBe 0
            runBlocking { failed.all().toList() }.shouldBeEmpty()

            shouldThrow<CliktError> { RemoveFailedCommand(catalog).parse(listOf("unknown-id")) }
            shouldThrow<CliktError> { RemoveFailedCommand(catalog).parse(listOf("id-1", "--all")) }
            shouldThrow<CliktError> { RetryFailedCommand(catalog).parse(listOf("id-1", "--all")) }
        }

        test("requires --transport when multiple distinct failure transports exist and rejects non-listable ones") {
            val f1 = InMemoryTransport()
            val f2 = InMemoryTransport()
            val multiCatalog =
                MessengerCatalog(
                    buses = emptyMap(),
                    transports = emptyMap(),
                    failureTransports = mapOf("a" to f1, "b" to f2),
                )

            shouldThrow<CliktError> { ShowFailedCommand(multiCatalog).parse(emptyList()) }
            ShowFailedCommand(multiCatalog).test("--transport a").statusCode shouldBe 0

            val nonListable =
                object : Transport {
                    override suspend fun send(envelope: Envelope): Envelope = envelope

                    override suspend fun receive(): List<Envelope> = emptyList()

                    override suspend fun ack(envelope: Envelope) {}

                    override suspend fun reject(envelope: Envelope) {}
                }
            val nonListableCatalog =
                MessengerCatalog(
                    buses = emptyMap(),
                    transports = emptyMap(),
                    failureTransports = mapOf("a" to nonListable),
                )
            shouldThrow<CliktError> { ShowFailedCommand(nonListableCatalog).parse(emptyList()) }
        }
    })
