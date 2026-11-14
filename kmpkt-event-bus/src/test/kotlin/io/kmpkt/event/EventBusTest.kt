package io.kmpkt.event

import io.kmpkt.messenger.fixture.RecordingMessageBus
import io.kmpkt.messenger.message.MessageHandler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private data class ItemChecked(
    val name: String,
) : Event

class EventBusTest :
    FunSpec({
        test("publishes an event onto the bus") {
            // Arrange
            val bus = RecordingMessageBus()
            val eventBus = EventBus(bus)

            // Act
            eventBus.publish(ItemChecked("Milch"))

            // Assert
            bus.dispatched shouldBe listOf(ItemChecked("Milch"))
        }

        test("an event handler is a message handler for its event") {
            // Arrange
            val handled = mutableListOf<ItemChecked>()
            val handler =
                object : EventHandler<ItemChecked> {
                    override suspend fun handle(event: ItemChecked) {
                        handled += event
                    }
                }

            // Act
            val asMessageHandler: MessageHandler<ItemChecked, Unit> = handler
            asMessageHandler(ItemChecked("Brot"))

            // Assert
            handled shouldBe listOf(ItemChecked("Brot"))
        }
    })
