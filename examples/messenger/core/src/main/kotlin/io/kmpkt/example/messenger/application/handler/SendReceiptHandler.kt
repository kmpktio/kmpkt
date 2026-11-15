package io.kmpkt.example.messenger.application.handler

import io.kmpkt.example.messenger.domain.command.SendReceipt
import io.kmpkt.example.messenger.domain.service.ReceiptMailer
import io.kmpkt.messenger.message.MessageHandler

class SendReceiptHandler(
    private val mailer: ReceiptMailer,
) : MessageHandler<SendReceipt, Unit> {
    override suspend fun invoke(message: SendReceipt) {
        mailer.send(message.orderId)
    }
}
