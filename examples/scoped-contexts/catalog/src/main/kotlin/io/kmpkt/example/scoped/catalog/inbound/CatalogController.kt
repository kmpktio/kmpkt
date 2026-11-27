package io.kmpkt.example.scoped.catalog.inbound

import io.kmpkt.di.view
import io.kmpkt.example.scoped.application.context.StoreRegistry
import io.kmpkt.example.scoped.domain.model.Product
import io.kmpkt.example.scoped.domain.model.StoreId

/**
 * Root-scoped controller serving catalog requests across all stores.
 *
 * Because controllers live in the root DI container, each request looks up the active store's
 * handle in [StoreRegistry] and projects it onto [CatalogView].
 */
class CatalogController(
    private val registry: StoreRegistry,
) {
    fun getCatalog(storeId: StoreId): List<Product> =
        withStore(storeId) {
            catalog.listProducts()
        }

    private fun <R> withStore(
        storeId: StoreId,
        block: CatalogView.() -> R,
    ): R =
        registry
            .get(storeId)
            .view<CatalogView>()
            .block()
}
