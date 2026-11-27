package io.kmpkt.example.scoped.catalog.inbound

import io.kmpkt.example.scoped.catalog.application.CatalogSyncWorker
import io.kmpkt.example.scoped.catalog.application.StoreCatalogService

/** Slice of the `Store` container exposed to the `:catalog` module. */
interface CatalogView {
    val catalog: StoreCatalogService
    val syncWorker: CatalogSyncWorker
}
