package io.kmpkt.example.scoped.orders.inbound

import io.kmpkt.example.scoped.orders.application.OrderService

/** Slice of the `Store` container exposed to the `:orders` module. */
interface OrderView {
    val orders: OrderService
}
