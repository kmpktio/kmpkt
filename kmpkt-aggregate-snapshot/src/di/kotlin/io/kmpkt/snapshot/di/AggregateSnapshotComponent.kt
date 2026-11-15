package io.kmpkt.snapshot.di

import io.kmpkt.aggregate.di.AggregateComponent
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.event.store.di.EventStoreComponent
import io.kmpkt.snapshot.di.pass.DecorateSnapshotRepositoriesPass
import io.kmpkt.snapshot.strategy.SnapshotStrategy
import kotlin.reflect.KClass

public fun ContainerBuilder.aggregateSnapshot(block: AggregateSnapshotOptions.() -> Unit = {}): Unit =
    configure<AggregateSnapshotComponent, AggregateSnapshotOptions>(block)

public class AggregateSnapshotComponent : Component<AggregateSnapshotOptions> {
    override val requires: Set<KClass<out Component<*>>> = setOf(AggregateComponent::class, EventStoreComponent::class)

    override fun defaults(): AggregateSnapshotOptions = AggregateSnapshotOptions()

    override fun ContainerBuilder.register(config: AggregateSnapshotOptions) {
        val defaultStrategyId =
            config.strategy?.let { strategyDecl ->
                ServiceId("aggregate.snapshot.strategy").also { id ->
                    service(type = strategyDecl.type, id = id) {
                        bind<SnapshotStrategy>()
                        strategyDecl.arguments.forEach { (param, arg) -> argument(param, arg) }
                    }
                }
            }

        val perAggregateIds = LinkedHashMap<TypeRef, ServiceId>()
        config.perAggregate.forEach { (aggregateType, strategyDecl) ->
            val id = ServiceId("aggregate.snapshot.strategy.${aggregateType.qualifiedName}")
            service(type = strategyDecl.type, id = id) {
                strategyDecl.arguments.forEach { (param, arg) -> argument(param, arg) }
            }
            perAggregateIds[aggregateType] = id
        }

        if (defaultStrategyId != null || perAggregateIds.isNotEmpty()) {
            addPass(DecorateSnapshotRepositoriesPass(perAggregateIds, defaultStrategyId), priority = 5)
        }
    }
}
