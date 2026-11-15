package io.kmpkt.example.messenger.domain.command

import io.kmpkt.messenger.message.Message

data class PlaceOrder(
    val orderId: String,
    val amount: Int,
    val actor: String,
) : Message
