package io.kmpkt.query

import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.handler.exception.HandlerFailedException
import io.kmpkt.messenger.handler.stamp.HandledStamp
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.reflect.KClass

private data class FindList(
    val listId: String,
) : Query<String>

private class DummyHandler : MessageHandler<Message, Any?> {
    override suspend fun invoke(message: Message): Any? = null
}

private class StubMessageBus(
    private val answer: Any?,
) : MessageBus {
    val asked = mutableListOf<Message>()

    @Suppress("UNCHECKED_CAST")
    override suspend fun dispatch(message: Message): Envelope {
        asked += message
        return Envelope(message).with(
            HandledStamp(
                result = answer,
                handlerClass = DummyHandler::class as KClass<MessageHandler<*, *>>,
                handledAt = Instant.EPOCH,
            ),
        )
    }

    override suspend fun dispatch(envelope: Envelope): Envelope = envelope
}

class QueryBusTest :
    FunSpec({
        test("asks a query on the bus and returns the handler result") {
            // Arrange
            val bus = StubMessageBus("Groceries")
            val queryBus = QueryBus(bus)

            // Act
            val result = queryBus.ask(FindList("list-1"))

            // Assert
            result shouldBe "Groceries"
            bus.asked shouldBe listOf(FindList("list-1"))
        }

        test("a query handler is the message handler for its query") {
            // Arrange
            val handler =
                object : QueryHandler<FindList, String> {
                    override suspend fun handle(query: FindList): String = "List:${query.listId}"
                }

            // Act
            val asMessageHandler: MessageHandler<FindList, String> = handler
            val result = asMessageHandler(FindList("list-2"))

            // Assert
            result shouldBe "List:list-2"
        }

        test("unwraps a single HandlerFailedException cause") {
            // Arrange
            val failure = IllegalStateException("denied")
            val bus =
                object : MessageBus {
                    override suspend fun dispatch(message: Message): Envelope = throw HandlerFailedException(message, failure)

                    override suspend fun dispatch(envelope: Envelope): Envelope = envelope
                }
            val queryBus = QueryBus(bus)

            // Act
            val thrown = shouldThrow<IllegalStateException> { queryBus.ask(FindList("list-3")) }

            // Assert
            thrown shouldBe failure
        }
    })
