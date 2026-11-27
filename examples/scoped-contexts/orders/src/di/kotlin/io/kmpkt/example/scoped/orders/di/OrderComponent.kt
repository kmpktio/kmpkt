package io.kmpkt.example.scoped.orders.di

import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.example.scoped.application.context.StoreContext
import io.kmpkt.example.scoped.orders.application.OrderService
import io.kmpkt.example.scoped.orders.application.StoreOrderRepository
import io.kmpkt.example.scoped.orders.inbound.OrderView

/** Contributes store-scoped order persistence, submission, and [OrderView] to [StoreContext]. */
class OrderComponent : SimpleComponent {
    override fun ContainerBuilder.register() {
        discover("io.kmpkt.example.scoped.orders.inbound")

        context<StoreContext> {
            service<StoreOrderRepository>()
            service<OrderService>()
            view<OrderView>()
        }
    }
}
