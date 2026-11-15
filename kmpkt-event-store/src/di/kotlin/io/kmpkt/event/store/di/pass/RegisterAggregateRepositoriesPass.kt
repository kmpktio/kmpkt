package io.kmpkt.event.store.di.pass

import io.kmpkt.aggregate.di.RegisteredAggregate
import io.kmpkt.aggregate.factory.AggregateFactory
import io.kmpkt.aggregate.repository.AggregateRepository
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.event.store.repository.DefaultAggregateRepository

/** Registers a [DefaultAggregateRepository] for every aggregate declared via [io.kmpkt.aggregate.di.AggregateComponent]. */
internal class RegisterAggregateRepositoriesPass : BuilderPass {
    override fun process(container: ContainerBuilder) {
        val repositoryImplBase = typeRef<DefaultAggregateRepository<*>>()
        val repositoryInterfaceBase = typeRef<AggregateRepository<*>>()
        val factoryBase = typeRef<AggregateFactory<*>>()

        container.tagged<RegisteredAggregate>().forEach { tagged ->
            val typeArg = TypeArgument.Of(tagged.tag.aggregateType)
            val implType = repositoryImplBase.copy(arguments = listOf(typeArg))
            val interfaceType = repositoryInterfaceBase.copy(arguments = listOf(typeArg))
            val factoryType = factoryBase.copy(arguments = listOf(typeArg))

            container.service(
                type = implType,
                id = ServiceId(implType.toString()),
            ) {
                bind(interfaceType)
                argument("aggregateType", Argument.Service(ServiceReference.ById(tagged.definition.id)))
                argument("factory", Argument.Service(ServiceReference.ByKey(ServiceKey(factoryType))))
            }
        }
    }
}
