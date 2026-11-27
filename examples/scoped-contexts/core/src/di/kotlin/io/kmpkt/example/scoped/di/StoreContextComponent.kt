package io.kmpkt.example.scoped.di

import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.example.scoped.application.context.StoreConfiguration
import io.kmpkt.example.scoped.application.context.StoreContext
import io.kmpkt.example.scoped.outbound.database.StoreDatabase
import io.kmpkt.example.scoped.outbound.erp.StoreErpClient
import io.kmpkt.example.scoped.outbound.filesystem.StoreFileSystem

/** Registers root store infrastructure and the shared [StoreContext] child container with store-scoped factories. */
class StoreContextComponent : SimpleComponent {
    override fun ContainerBuilder.register() {
        discover("io.kmpkt.example.scoped.outbound.erp")
        discover("io.kmpkt.example.scoped.application.context")

        context<StoreContext, StoreConfiguration> {
            factory(StoreFileSystem::create)
            factory(StoreDatabase::create)
            factory(StoreErpClient::create)
        }
    }
}
