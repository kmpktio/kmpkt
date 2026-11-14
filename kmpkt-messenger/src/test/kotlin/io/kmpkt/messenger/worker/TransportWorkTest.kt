package io.kmpkt.messenger.worker

import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.retry.MultiplierRetryStrategy
import io.kmpkt.messenger.stamp.DelayStamp
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.stamp.RedeliveryStamp
import io.kmpkt.messenger.stamp.SentToFailureTransportStamp
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.worker.ManagedWorker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger(TransportWorkTest::class.java)

private class QueueTransport : Transport {
    private val pending = mutableListOf<Envelope>()
    val acked = mutableListOf<Envelope>()
    val rejected = mutableListOf<Envelope>()

    fun enqueue(envelope: Envelope) {
        pending += envelope
    }

    override suspend fun send(envelope: Envelope): Envelope {
        pending += envelope
        return envelope
    }

    override suspend fun receive(): List<Envelope> = if (pending.isEmpty()) emptyList() else listOf(pending.removeAt(0))

    override suspend fun ack(envelope: Envelope) {
        acked += envelope
    }

    override suspend fun reject(envelope: Envelope) {
        rejected += envelope
    }
}

private class RecordingMessageBus(
    private val onDispatch: suspend (Envelope) -> Unit = {},
) : MessageBus {
    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

    override suspend fun dispatch(envelope: Envelope): Envelope {
        onDispatch(envelope)
        return envelope
    }
}

class TransportWorkTest :
    FunSpec({
        test("dispatches each received envelope, stamped with the transport name, and acks it") {
            // Arrange
            val transport = QueueTransport()
            val envelope = Envelope(Greet("Milch"))
            transport.enqueue(envelope)
            val dispatched = CompletableDeferred<Envelope>()
            val bus = RecordingMessageBus { dispatched.complete(it) }
            val scope = CoroutineScope(Dispatchers.Default)

            // Act
            ManagedWorker("queue", TransportWork(transport, "queue", bus, logger), logger, pollInterval = 5.milliseconds).start()
            val result = withTimeout(1000) { dispatched.await() }
            scope.cancel()

            // Assert
            result shouldBe envelope.with(ReceivedStamp("queue"))
            transport.acked shouldBe listOf(envelope)
        }

        test("rejects an envelope whose dispatch threw, instead of acking it") {
            // Arrange
            val transport = QueueTransport()
            val envelope = Envelope(Greet("Milch"))
            transport.enqueue(envelope)
            val bus = RecordingMessageBus { throw RuntimeException("boom") }
            val scope = CoroutineScope(Dispatchers.Default)

            // Act
            ManagedWorker("queue", TransportWork(transport, "queue", bus, logger), logger, pollInterval = 5.milliseconds).start()
            withTimeout(1000) {
                while (transport.rejected.isEmpty()) delay(5)
            }
            scope.cancel()

            // Assert
            transport.rejected shouldBe listOf(envelope)
            transport.acked.shouldBeEmpty()
        }

        test("with a strategy, a failed envelope is sent back for another try before it is rejected") {
            // Arrange
            val transport = QueueTransport()
            val envelope = Envelope(Greet("Milch"))
            transport.enqueue(envelope)
            var failuresLeft = 1
            val bus = RecordingMessageBus { if (failuresLeft-- > 0) throw RuntimeException("boom") }
            val work =
                TransportWork(transport, "queue", bus, logger, MultiplierRetryStrategy(maxRetries = 3, delay = 2.seconds, jitter = 0.0))

            // Act
            work.run()
            work.run()

            // Assert
            val redelivered = envelope.with(RedeliveryStamp(1), DelayStamp(2.seconds))
            transport.rejected shouldBe listOf(envelope)
            transport.acked shouldBe listOf(redelivered)
        }

        test("with a strategy that has had enough, a failed envelope is rejected and not sent back") {
            // Arrange
            val transport = QueueTransport()
            val envelope = Envelope(Greet("Milch")).with(RedeliveryStamp(1))
            transport.enqueue(envelope)
            val bus = RecordingMessageBus { throw RuntimeException("boom") }
            val work = TransportWork(transport, "queue", bus, logger, MultiplierRetryStrategy(maxRetries = 1))

            // Act
            val first = work.run()
            val second = work.run()

            // Assert
            first shouldBe 1
            second shouldBe 0
            transport.rejected shouldBe listOf(envelope)
        }

        test("with a failure transport, an envelope that is given up on is put aside there, and rejected where it came from") {
            // Arrange
            val transport = QueueTransport()
            val failed = InMemoryTransport()
            val envelope = Envelope(Greet("Milch"))
            transport.enqueue(envelope)
            val bus = RecordingMessageBus { throw IllegalStateException("boom") }
            val work = TransportWork(transport, "queue", bus, logger, failureTransport = failed)

            // Act
            work.run()

            // Assert
            transport.rejected shouldBe listOf(envelope)
            failed.pending() shouldBe
                listOf(envelope.with(SentToFailureTransportStamp("queue", "IllegalStateException", "boom")))
        }

        test("retries come first: the failure transport only gets what the strategy has given up on") {
            // Arrange
            val transport = QueueTransport()
            val failed = InMemoryTransport()
            transport.enqueue(Envelope(Greet("Milch")))
            val bus = RecordingMessageBus { throw IllegalStateException("boom") }
            val work =
                TransportWork(transport, "queue", bus, logger, MultiplierRetryStrategy(maxRetries = 1, jitter = 0.0), failed)

            // Act
            work.run()
            val afterFirst = failed.pending()
            work.run()

            // Assert
            afterFirst shouldBe emptyList()
            failed.pending().map { RedeliveryStamp.retryCountOf(it) } shouldBe listOf(1)
        }

        test("consuming the failure transport itself does not feed a failing message back into it") {
            // Arrange
            val failed = InMemoryTransport()
            val putAside = Envelope(Greet("Milch")).with(SentToFailureTransportStamp("queue", "IllegalStateException", "boom"))
            failed.send(putAside)
            val bus = RecordingMessageBus { throw IllegalStateException("still broken") }
            val work = TransportWork(failed, "failed", bus, logger, failureTransport = failed)

            // Act
            work.run()

            // Assert
            failed.pending() shouldBe emptyList()
            failed.rejected() shouldBe listOf(putAside)
        }
    })
