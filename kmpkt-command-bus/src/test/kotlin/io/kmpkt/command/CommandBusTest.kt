package io.kmpkt.command

import io.kmpkt.messenger.fixture.RecordingMessageBus
import io.kmpkt.messenger.message.MessageHandler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private data class Dispatchable(
    val listId: String,
) : Command

class CommandBusTest :
    FunSpec({
        test("puts a command on the bus") {
            // Arrange
            val bus = RecordingMessageBus()
            val commandBus = CommandBus(bus)

            // Act
            commandBus.dispatch(Dispatchable("list-1"))

            // Assert
            bus.dispatched shouldBe listOf(Dispatchable("list-1"))
        }

        test("a command handler is the message handler for its command") {
            // Arrange
            val handled = mutableListOf<Dispatchable>()
            val handler =
                object : CommandHandler<Dispatchable> {
                    override suspend fun handle(command: Dispatchable) {
                        handled += command
                    }
                }

            // Act
            val asMessageHandler: MessageHandler<Dispatchable, Unit> = handler
            asMessageHandler(Dispatchable("list-2"))

            // Assert
            handled shouldBe listOf(Dispatchable("list-2"))
        }
    })
