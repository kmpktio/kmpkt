package io.kmpkt.example.messenger.application.handler

import io.kmpkt.example.messenger.domain.event.OrderPlaced
import io.kmpkt.messenger.message.MessageHandler
import org.slf4j.Logger

class AnalyticsOrderPlacedHandler(
    private val logger: Logger,
) : MessageHandler<OrderPlaced, Unit> {
    override suspend fun invoke(message: OrderPlaced) {
        logger.info("analytics: recorded revenue ${message.amount} for ${message.orderId}")
    }
}
