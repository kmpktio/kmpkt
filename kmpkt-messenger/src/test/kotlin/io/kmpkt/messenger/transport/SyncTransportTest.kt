package io.kmpkt.messenger.transport

import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.fixture.GreetHandler
import io.kmpkt.messenger.handler.exception.HandlerFailedException
import io.kmpkt.messenger.handler.stamp.HandledStamp
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.retry.MultiplierRetryStrategy
import io.kmpkt.messenger.retry.RecoverableException
import io.kmpkt.messenger.retry.UnrecoverableException
import io.kmpkt.messenger.stamp.ErrorDetailsStamp
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.stamp.RedeliveryStamp
import io.kmpkt.messenger.stamp.SentToFailureTransportStamp
import io.kmpkt.messenger.transport.stamp.SentStamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.reflect.KClass

private val now = Instant.parse("2026-01-01T00:00:00Z")

private class RecordingMessageBus(
    private val answer: (Envelope) -> Envelope = { it },
) : MessageBus {
    val dispatched = mutableListOf<Envelope>()

    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

    override suspend fun dispatch(envelope: Envelope): Envelope {
        dispatched += envelope
        return answer(envelope)
    }
}

class SyncTransportTest :
    FunSpec({
        test("hands the message back to the bus, marked as received") {
            // Arrange
            val bus = RecordingMessageBus()
            val transport = SyncTransport(lazy { bus }, name = "sync")

            // Act
            transport.send(Envelope(Greet("Milch")))

            // Assert
            val redispatched = bus.dispatched.single()
            redispatched.last<ReceivedStamp>()?.transportName shouldBe "sync"
        }

        test("marks the message as received under the name it was routed as") {
            // Arrange
            val bus = RecordingMessageBus()
            val transport = SyncTransport(lazy { bus }, name = "sync")

            // Act
            transport.send(Envelope(Greet("Milch")).with(SentStamp("inline", now)))

            // Assert
            val redispatched = bus.dispatched.single()
            redispatched.last<ReceivedStamp>()?.transportName shouldBe "inline"
        }

        test("returns what the bus made of it, so a handler's result survives") {
            // Arrange
            val handled = Envelope(Greet("Brot"))
            val transport = SyncTransport(lazy { RecordingMessageBus(answer = { handled }) }, name = "sync")

            // Act + Assert
            transport.send(Envelope(Greet("Milch"))) shouldBe handled
        }

        test("resolves the bus only when a message is actually sent") {
            // Arrange + Act: a Lazy that would blow up if touched during construction
            val transport = SyncTransport(lazy { error("bus resolved too early") }, name = "sync")

            // Assert
            shouldThrow<IllegalStateException> { transport.send(Envelope(Greet("Milch"))) }
        }

        test("receiving, acking and rejecting are mistakes, not no-ops") {
            // Arrange
            val transport = SyncTransport(lazy { RecordingMessageBus() }, name = "sync")

            // Act + Assert
            shouldThrow<IllegalStateException> { transport.receive() }
            shouldThrow<IllegalStateException> { transport.ack(Envelope(Greet("Milch"))) }
            shouldThrow<IllegalStateException> { transport.reject(Envelope(Greet("Milch"))) }
        }

        test("without a strategy the first failure is the caller's") {
            // Arrange
            val bus = RecordingMessageBus { throw Lost() }
            val transport = SyncTransport(lazy { bus }, name = "sync")

            // Act + Assert
            shouldThrow<Lost> { transport.send(Envelope(Greet("Milch"))) }
            bus.dispatched.size shouldBe 1
        }

        test("with a strategy a failed message is handled again at once, counted, until it goes through") {
            // Arrange
            var failuresLeft = 2
            val bus = RecordingMessageBus { if (failuresLeft-- > 0) throw Lost() else it }
            val transport = SyncTransport(lazy { bus }, name = "sync", retryStrategy = MultiplierRetryStrategy(maxRetries = 2))

            // Act
            val result = transport.send(Envelope(Greet("Milch")))

            // Assert
            bus.dispatched.map { RedeliveryStamp.retryCountOf(it) } shouldBe listOf(0, 1, 2)
            bus.dispatched.map { it.all<ReceivedStamp>().size } shouldBe listOf(1, 1, 1)
            RedeliveryStamp.retryCountOf(result) shouldBe 2
        }

        test("gives up with the last failure once the strategy has had enough") {
            // Arrange
            val bus = RecordingMessageBus { throw Lost() }
            val transport = SyncTransport(lazy { bus }, name = "sync", retryStrategy = MultiplierRetryStrategy(maxRetries = 1))

            // Act + Assert
            shouldThrow<Lost> { transport.send(Envelope(Greet("Milch"))) }
            bus.dispatched.size shouldBe 2
        }

        test("never retries a failure that is marked unrecoverable, whatever the strategy would allow") {
            // Arrange
            val bus = RecordingMessageBus { throw Invalid() }
            val transport = SyncTransport(lazy { bus }, "sync", MultiplierRetryStrategy())

            // Act + Assert
            shouldThrow<Invalid> { transport.send(Envelope(Greet("Milch"))) }
            bus.dispatched.size shouldBe 1
        }

        test("with a failure transport, a message that is given up on is put aside there instead of thrown") {
            // Arrange
            val bus = RecordingMessageBus { throw IllegalStateException("boom") }
            val failed = InMemoryTransport()
            val transport = SyncTransport(lazy { bus }, "sync", MultiplierRetryStrategy(maxRetries = 1), failed)

            // Act
            val result = transport.send(Envelope(Greet("Milch")))

            // Assert
            bus.dispatched.size shouldBe 2
            failed.pending() shouldBe listOf(result)
            result.message shouldBe Greet("Milch")
            result.last<SentToFailureTransportStamp>() shouldBe SentToFailureTransportStamp("sync")
            result.last<ErrorDetailsStamp>() shouldBe ErrorDetailsStamp("IllegalStateException", "boom")
            RedeliveryStamp.retryCountOf(result) shouldBe 1
        }

        test("an unrecoverable failure goes to the failure transport without a retry, strategy or not") {
            // Arrange
            val bus = RecordingMessageBus { throw Invalid() }
            val failed = InMemoryTransport()

            // Act
            SyncTransport(lazy { bus }, "sync", MultiplierRetryStrategy(), failed).send(Envelope(Greet("Milch")))
            SyncTransport(lazy { bus }, "sync", failureTransport = failed).send(Envelope(Greet("Brot")))

            // Assert
            bus.dispatched.size shouldBe 2
            failed.pending().map { it.message } shouldBe listOf(Greet("Milch"), Greet("Brot"))
        }

        test("a message that was put aside once is not put aside again when it fails while being looked at") {
            // Arrange
            val bus = RecordingMessageBus { throw Lost() }
            val failed = InMemoryTransport()
            val transport = SyncTransport(lazy { bus }, "sync", failureTransport = failed)
            val putAside = Envelope(Greet("Milch")).with(SentToFailureTransportStamp("async"))

            // Act + Assert
            shouldThrow<Lost> { transport.send(putAside) }
            failed.pending() shouldBe emptyList()
        }

        test("a retry starts from what handling got done, so a handler that succeeded is not run again") {
            // Arrange
            val handled = HandledStamp(result = "done", handlerClass = handlerClass, handledAt = now)
            var first = true
            val bus =
                RecordingMessageBus { envelope ->
                    if (first) {
                        first = false
                        throw HandlerFailedException(envelope.message, Lost(), envelope = envelope.with(handled))
                    }
                    envelope
                }
            val transport = SyncTransport(lazy { bus }, name = "sync", retryStrategy = MultiplierRetryStrategy())

            // Act
            transport.send(Envelope(Greet("Milch")))

            // Assert
            bus.dispatched[1].last<HandledStamp>() shouldBe handled
            bus.dispatched[1].all<ReceivedStamp>().size shouldBe 1
        }
    })

private class Lost :
    RuntimeException("lost the race"),
    RecoverableException

private class Invalid :
    RuntimeException("will never work"),
    UnrecoverableException

@Suppress("UNCHECKED_CAST")
private val handlerClass = GreetHandler::class as KClass<MessageHandler<*, *>>
