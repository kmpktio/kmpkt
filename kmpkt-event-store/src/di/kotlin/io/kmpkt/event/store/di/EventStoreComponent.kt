package io.kmpkt.event.store.di

import io.kmpkt.aggregate.di.AggregateComponent
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.service
import io.kmpkt.event.store.EventCheckpointPredicate
import io.kmpkt.event.store.EventStore
import io.kmpkt.event.store.NoOpEventCheckpointPredicate
import io.kmpkt.event.store.PersistentEventStore
import io.kmpkt.event.store.di.pass.RegisterAggregateRepositoriesPass
import io.kmpkt.persistence.AppendLog
import io.kmpkt.persistence.createAppendLog
import io.kmpkt.persistence.di.PersistenceComponent
import kotlin.reflect.KClass

public class EventStoreComponent : Component<EventStoreOptions> {
    override val requires: Set<KClass<out Component<*>>> = setOf(AggregateComponent::class, PersistenceComponent::class)

    override fun defaults(): EventStoreOptions = EventStoreOptions()

    override fun ContainerBuilder.register(config: EventStoreOptions) {
        service<NoOpEventCheckpointPredicate> { bind<EventCheckpointPredicate>() }

        factory(::createAppendLog, ServiceId("persistence.appendLog.${config.logName}")) {
            bind<AppendLog>(config.logName)
            argument("name", literal(config.logName))
        }
        service<PersistentEventStore> {
            bind<EventStore>()
            argument("appendLog", service<AppendLog>(config.logName))
        }

        addPass(RegisterAggregateRepositoriesPass(), priority = 10)
    }
}
