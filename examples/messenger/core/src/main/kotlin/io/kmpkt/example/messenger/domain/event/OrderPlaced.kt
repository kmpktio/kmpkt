package io.kmpkt.example.messenger.domain.event

import io.kmpkt.messenger.message.Message

data class OrderPlaced(
    val orderId: String,
    val amount: Int,
    val actor: String,
) : Message
