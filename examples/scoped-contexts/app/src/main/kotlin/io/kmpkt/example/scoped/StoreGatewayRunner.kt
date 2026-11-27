package io.kmpkt.example.scoped

import io.kmpkt.di.ApplicationRunner
import io.kmpkt.di.view
import io.kmpkt.example.scoped.application.context.StoreRegistry
import io.kmpkt.example.scoped.catalog.inbound.CatalogController
import io.kmpkt.example.scoped.catalog.inbound.CatalogView
import io.kmpkt.example.scoped.domain.model.StoreEvent
import io.kmpkt.example.scoped.domain.model.StoreId
import io.kmpkt.example.scoped.domain.model.StoreNotFound
import io.kmpkt.example.scoped.orders.domain.Order
import io.kmpkt.example.scoped.orders.inbound.OrderController
import io.kmpkt.example.scoped.outbound.erp.UpstreamErpClient

/** Simulates external store lifecycle events alongside catalog reads and POS order submissions. */
class StoreGatewayRunner(
    private val registry: StoreRegistry,
    private val catalogController: CatalogController,
    private val orderController: OrderController,
    private val erp: UpstreamErpClient,
) : ApplicationRunner {
    override fun run(args: Array<String>) {
        val berlin = StoreId("berlin")
        val munich = StoreId("munich")

        // 1. External config/lifecycle events open an isolated sub-container per active store.
        registry.onEvent(StoreEvent.StoreConnected(berlin, "https://erp.example/berlin"))
        registry.onEvent(StoreEvent.StoreConnected(munich, "https://erp.example/munich"))

        val berlinWorker =
            registry
                .get(berlin)
                .view<CatalogView>()
                .syncWorker
        check(!berlinWorker.closed)

        // 2. Both feature modules (:catalog and :orders) operate on each store's isolated state.
        check(catalogController.getCatalog(berlin).map { it.sku } == listOf("SKU-101", "SKU-102"))
        check(catalogController.getCatalog(munich).map { it.sku } == listOf("SKU-201"))

        orderController.placeOrder(berlin, "SKU-101", 2)
        orderController.placeOrder(munich, "SKU-201", 1)
        check(orderController.listOrders(berlin) == listOf(Order("SKU-101", 2)))
        check(orderController.listOrders(munich) == listOf(Order("SKU-201", 1)))
        check(erp.submittedOrders(berlin) == listOf("SKU-101" to 2))
        check(erp.submittedOrders(munich) == listOf("SKU-201" to 1))

        // 3. Replaying StoreConnected with identical config is idempotent and keeps the warm scope alive.
        registry.onEvent(StoreEvent.StoreConnected(berlin, "https://erp.example/berlin"))
        check(registry.get(berlin).view<CatalogView>().syncWorker === berlinWorker)
        check(!berlinWorker.closed)

        // 4. Reconnecting with changed config closes the stale handle and opens a fresh scope.
        registry.onEvent(StoreEvent.StoreConnected(berlin, "https://erp.example/berlin-v2"))
        check(berlinWorker.closed)
        val reconnectedWorker =
            registry
                .get(berlin)
                .view<CatalogView>()
                .syncWorker
        check(!reconnectedWorker.closed)

        // 5. StoreDisconnected tears down Berlin's scope, stops its worker, and rejects further requests.
        registry.onEvent(StoreEvent.StoreDisconnected(berlin))
        check(reconnectedWorker.closed)
        check(runCatching { catalogController.getCatalog(berlin) }.exceptionOrNull() is StoreNotFound)
        check(runCatching { orderController.placeOrder(berlin, "SKU-101", 1) }.exceptionOrNull() is StoreNotFound)

        check(catalogController.getCatalog(munich).map { it.sku } == listOf("SKU-201"))
        registry.onEvent(StoreEvent.StoreDisconnected(munich))
    }
}
