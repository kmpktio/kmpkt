package io.kmpkt.example.scoped.outbound.erp

import io.kmpkt.example.scoped.application.context.StoreConfiguration
import io.kmpkt.example.scoped.domain.model.Product

/** Store-scoped ERP client bound to one store's endpoint and backed by the shared [UpstreamErpClient]. */
class StoreErpClient(
    private val config: StoreConfiguration,
    private val upstream: UpstreamErpClient,
) {
    fun fetchCatalog(): List<Product> = upstream.fetchCatalog(config.storeId, config.erpEndpoint)

    fun submitOrder(
        sku: String,
        quantity: Int,
    ) {
        upstream.submitOrder(config.storeId, config.erpEndpoint, sku, quantity)
    }

    companion object {
        /** Creates a [StoreErpClient] bound to [config] over the shared [upstream] client. */
        fun create(
            config: StoreConfiguration,
            upstream: UpstreamErpClient,
        ): StoreErpClient = StoreErpClient(config, upstream)
    }
}
