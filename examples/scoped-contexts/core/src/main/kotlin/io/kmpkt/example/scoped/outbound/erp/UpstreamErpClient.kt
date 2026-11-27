package io.kmpkt.example.scoped.outbound.erp

import io.kmpkt.example.scoped.domain.model.Product
import io.kmpkt.example.scoped.domain.model.StoreId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Shared root client communicating with each store's upstream ERP endpoint. */
class UpstreamErpClient {
    private val catalogs =
        mapOf(
            StoreId("berlin") to
                listOf(
                    Product("SKU-101", "Espresso Roast", 1290),
                    Product("SKU-102", "Oat Milk", 240),
                ),
            StoreId("munich") to
                listOf(
                    Product("SKU-201", "Pretzel Box", 490),
                ),
        )

    private val submittedOrders = ConcurrentHashMap<StoreId, CopyOnWriteArrayList<Pair<String, Int>>>()

    fun fetchCatalog(
        storeId: StoreId,
        @Suppress("unused") erpEndpoint: String,
    ): List<Product> = catalogs[storeId].orEmpty()

    fun submitOrder(
        storeId: StoreId,
        @Suppress("unused") erpEndpoint: String,
        sku: String,
        quantity: Int,
    ) {
        submittedOrders.computeIfAbsent(storeId) { CopyOnWriteArrayList() }.add(sku to quantity)
    }

    fun submittedOrders(storeId: StoreId): List<Pair<String, Int>> =
        submittedOrders[storeId]?.toList().orEmpty()
}
