package io.kmpkt.example.scoped.orders.domain

/** POS order line placed for a connected store. */
data class Order(
    val sku: String,
    val quantity: Int,
)
