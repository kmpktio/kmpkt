package io.kmpkt.example.scoped.orders.inbound

import io.kmpkt.di.view
import io.kmpkt.example.scoped.application.context.StoreRegistry
import io.kmpkt.example.scoped.domain.model.StoreId
import io.kmpkt.example.scoped.orders.domain.Order

/**
 * Root-scoped controller accepting and listing POS orders across all stores.
 *
 * Because controllers live in the root DI container, each request looks up the active store's
 * handle in [StoreRegistry] and projects it onto [OrderView].
 */
class OrderController(
    private val registry: StoreRegistry,
) {
    fun placeOrder(
        storeId: StoreId,
        sku: String,
        quantity: Int,
    ) {
        withStore(storeId) {
            orders.placeOrder(sku, quantity)
        }
    }

    fun listOrders(storeId: StoreId): List<Order> =
        withStore(storeId) {
            orders.listOrders()
        }

    private fun <R> withStore(
        storeId: StoreId,
        block: OrderView.() -> R,
    ): R =
        registry
            .get(storeId)
            .view<OrderView>()
            .block()
}
