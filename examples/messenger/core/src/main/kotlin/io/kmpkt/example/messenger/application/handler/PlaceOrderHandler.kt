package io.kmpkt.example.messenger.application.handler

import io.kmpkt.example.messenger.domain.command.PlaceOrder
import io.kmpkt.example.messenger.domain.event.OrderPlaced
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.message.MessageHandler

class PlaceOrderHandler(
    private val events: MessageBus,
) : MessageHandler<PlaceOrder, Unit> {
    override suspend fun invoke(message: PlaceOrder) {
        // Publish domain event after accepting the command; subscribers run on the dedicated events bus.
        events.dispatch(
            OrderPlaced(
                orderId = message.orderId,
                amount = message.amount,
                actor = message.actor,
            ),
        )
    }
}
