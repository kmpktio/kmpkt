package io.kmpkt.example.scoped.orders.application

import io.kmpkt.example.scoped.orders.domain.Order
import io.kmpkt.example.scoped.outbound.database.StoreDatabase

/** Store-isolated repository recording POS orders inside the store's [StoreDatabase]. */
class StoreOrderRepository(
    private val database: StoreDatabase,
) {
    fun save(order: Order) {
        database.table<Order>("orders") += order
    }

    fun findAll(): List<Order> = database.table<Order>("orders").toList()
}
