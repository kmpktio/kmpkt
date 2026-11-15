package io.kmpkt.projection.di.pass

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.TaggedService
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.di.compiler.TypedArgument
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.projection.Projector
import io.kmpkt.projection.di.ProjectorTag
import io.kmpkt.projection.di.RegisteredProjection
import kotlin.reflect.KClass

internal class RegisterProjectionsPass(
    private val projections: List<RegisteredProjection>,
) : BuilderPass {
    override fun process(container: ContainerBuilder) {
        val projectors = container.tagged<ProjectorTag>()

        val byRowType =
            projectors.mapNotNull { tagged ->
                typesOf(container, tagged.definition)?.let { (eventType, rowType) ->
                    Triple(rowType, eventType, tagged)
                }
            }

        projections.forEach { projection ->
            val entries =
                byRowType
                    .filter { (rowType, _, _) -> rowType == projection.rowType }
                    .groupBy({ it.second }, { it.third })
                    .map { (eventType, matching) -> entry(eventType, matching) }

            val initialType =
                typeRef<Map<KClass<out DomainEvent<*>>, List<Projector<*, *>>>>().let { mapType ->
                    val listArg = mapType.arguments[1] as TypeArgument.Of
                    val projectorArg = listArg.type.arguments[0] as TypeArgument.Of
                    val typedProjector =
                        projectorArg.type.copy(
                            arguments = listOf(TypeArgument.Star, TypeArgument.Of(projection.rowType)),
                        )
                    val typedList = listArg.type.copy(arguments = listOf(TypeArgument.Of(typedProjector)))
                    mapType.copy(arguments = listOf(mapType.arguments[0], TypeArgument.Of(typedList)))
                }

            container.update(projection.executorId) {
                it.copy(typedArguments = it.typedArguments + TypedArgument(initialType, Argument.Dictionary(entries)))
            }
        }
    }

    private fun entry(
        event: TypeRef,
        projectors: List<TaggedService<ProjectorTag>>,
    ): Pair<Argument, Argument> =
        Argument.Literal(ConstValue.ClassLiteral(event)) to
            Argument.Sequence(projectors.map { Argument.Service(ServiceReference.ById(it.definition.id)) })

    private fun typesOf(
        container: ContainerBuilder,
        projector: ServiceDefinition,
    ): Pair<TypeRef, TypeRef>? {
        val args = container.types.typeArguments(projector.type, of = typeRef<Projector<*, *>>())
        val event = args.getOrNull(0)
        val row = args.getOrNull(1)

        if (event !is TypeArgument.Of || row !is TypeArgument.Of) {
            container.diagnostics.error(
                "Cannot tell event and row types of ${projector.describe()}: its type ${projector.type} does not say what E and R " +
                    "of Projector<E, R> are.",
            )
            return null
        }

        return event.type to row.type
    }
}
