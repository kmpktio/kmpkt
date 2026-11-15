package io.kmpkt.aggregate.di

import io.kmpkt.aggregate.value.AggregateType
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.typeRef

public fun ContainerBuilder.aggregate(block: AggregateOptions.() -> Unit = {}): Unit =
    configure<AggregateComponent, AggregateOptions>(block)

public class AggregateComponent : Component<AggregateOptions> {
    override fun defaults(): AggregateOptions = AggregateOptions()

    override fun ContainerBuilder.register(config: AggregateOptions) {
        val aggregateTypeBase = typeRef<AggregateType<*>>()

        config.aggregates.forEach { registration ->
            val typeArg = TypeArgument.Of(registration.aggregateType)
            val aggregateTypeType = aggregateTypeBase.copy(arguments = listOf(typeArg))
            val aggregateTypeId = ServiceId("aggregate.type.${registration.name}")

            service(
                type = aggregateTypeType,
                id = aggregateTypeId,
            ) {
                argument("name", literal(registration.name))
                tag(RegisteredAggregate(registration.aggregateType, registration.name))
            }
        }

        config.configurators.forEach { it() }
    }
}
