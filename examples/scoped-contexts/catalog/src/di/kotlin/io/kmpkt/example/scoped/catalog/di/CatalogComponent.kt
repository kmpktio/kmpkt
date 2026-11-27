package io.kmpkt.example.scoped.catalog.di

import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.example.scoped.application.context.StoreContext
import io.kmpkt.example.scoped.catalog.application.CatalogSyncWorker
import io.kmpkt.example.scoped.catalog.application.StoreCatalogService
import io.kmpkt.example.scoped.catalog.application.StoreCatalogStorage
import io.kmpkt.example.scoped.catalog.inbound.CatalogView

/** Contributes catalog synchronization services and [CatalogView] to the shared [StoreContext]. */
class CatalogComponent : SimpleComponent {
    override fun ContainerBuilder.register() {
        discover("io.kmpkt.example.scoped.catalog.inbound")

        context<StoreContext> {
            service<StoreCatalogStorage>()
            service<CatalogSyncWorker> { eager() }
            service<StoreCatalogService>()
            view<CatalogView>()
        }
    }
}
