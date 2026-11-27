package io.kmpkt.example.scoped.orders.application

import io.kmpkt.example.scoped.orders.domain.Order
import io.kmpkt.example.scoped.outbound.erp.StoreErpClient

/** Store-scoped service persisting POS orders locally and forwarding them through [StoreErpClient]. */
class OrderService(
    private val repository: StoreOrderRepository,
    private val erp: StoreErpClient,
) {
    fun placeOrder(
        sku: String,
        quantity: Int,
    ) {
        repository.save(Order(sku = sku, quantity = quantity))
        erp.submitOrder(sku, quantity)
    }

    fun listOrders(): List<Order> = repository.findAll()
}
