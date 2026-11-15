package io.kmpkt.snapshot.di.pass

import io.kmpkt.aggregate.di.RegisteredAggregate
import io.kmpkt.aggregate.repository.AggregateRepository
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.event.store.EventCheckpointPredicate
import io.kmpkt.snapshot.event.SnapshotEventCheckpointPredicate
import io.kmpkt.snapshot.repository.SnapshotAggregateRepository

/**
 * Enables [SnapshotEventCheckpointPredicate] on the event store and wraps each snapshotted
 * [AggregateRepository] with [SnapshotAggregateRepository] and its configured strategy.
 */
internal class DecorateSnapshotRepositoriesPass(
    private val perAggregateStrategies: Map<TypeRef, ServiceId>,
    private val defaultStrategyId: ServiceId?,
) : BuilderPass {
    override fun process(container: ContainerBuilder) {
        val decoratorBase = typeRef<SnapshotAggregateRepository<*>>()
        val repositoryInterfaceBase = typeRef<AggregateRepository<*>>()

        var decoratedAny = false
        container.tagged<RegisteredAggregate>().forEach { tagged ->
            val strategyId = perAggregateStrategies[tagged.tag.aggregateType] ?: defaultStrategyId ?: return@forEach
            decoratedAny = true

            val typeArg = TypeArgument.Of(tagged.tag.aggregateType)
            val decoratorType = decoratorBase.copy(arguments = listOf(typeArg))
            val interfaceType = repositoryInterfaceBase.copy(arguments = listOf(typeArg))

            container.service(
                type = decoratorType,
                id = ServiceId(decoratorType.toString()),
            ) {
                decorates(ServiceKey(interfaceType))
                argument("inner", Argument.Inner)
                argument("snapshotStrategy", Argument.Service(ServiceReference.ById(strategyId)))
            }
        }

        if (decoratedAny) {
            container.find<EventCheckpointPredicate>()?.let { container.remove(it.id) }
            container.service<SnapshotEventCheckpointPredicate> {
                bind<EventCheckpointPredicate>()
            }
        }
    }
}
