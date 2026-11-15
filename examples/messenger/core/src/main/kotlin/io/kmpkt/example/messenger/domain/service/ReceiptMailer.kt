package io.kmpkt.example.messenger.domain.service

interface ReceiptMailer {
    fun send(orderId: String)
}
