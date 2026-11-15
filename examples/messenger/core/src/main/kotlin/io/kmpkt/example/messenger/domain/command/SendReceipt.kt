package io.kmpkt.example.messenger.domain.command

import io.kmpkt.messenger.message.Message

data class SendReceipt(
    val orderId: String,
) : Message
