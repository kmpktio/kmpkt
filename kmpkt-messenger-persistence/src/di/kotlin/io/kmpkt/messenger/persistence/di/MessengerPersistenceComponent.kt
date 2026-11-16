package io.kmpkt.messenger.persistence.di

import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.messenger.persistence.di.pass.WirePersistentTransportsPass
import io.kmpkt.messenger.persistence.middleware.TransactionMiddleware
import io.kmpkt.messenger.persistence.worker.PersistenceWorkLock
import io.kmpkt.persistence.di.PersistenceComponent
import io.kmpkt.worker.WorkLock
import kotlin.reflect.KClass

public class MessengerPersistenceComponent : SimpleComponent {
    override val requires: Set<KClass<out Component<*>>> = setOf(MessengerComponent::class, PersistenceComponent::class)

    override fun ContainerBuilder.register() {
        service<TransactionMiddleware>()
        service<PersistenceWorkLock> { bind<WorkLock>() }
        addPass(WirePersistentTransportsPass())
    }
}
