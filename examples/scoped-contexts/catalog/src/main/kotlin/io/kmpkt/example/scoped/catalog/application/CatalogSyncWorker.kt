package io.kmpkt.example.scoped.catalog.application

import io.kmpkt.example.scoped.outbound.erp.StoreErpClient

/**
 * Eager store-scoped worker that preloads the catalog when the store context opens and
 * implements [AutoCloseable] so `StoreRegistry` tears it down automatically on disconnect.
 */
class CatalogSyncWorker(
    private val erp: StoreErpClient,
    private val storage: StoreCatalogStorage,
) : AutoCloseable {
    var closed: Boolean = false
        private set

    init {
        syncNow()
    }

    fun syncNow() {
        if (closed) return
        storage.replaceAll(
            erp.fetchCatalog(),
        )
    }

    override fun close() {
        closed = true
    }
}
