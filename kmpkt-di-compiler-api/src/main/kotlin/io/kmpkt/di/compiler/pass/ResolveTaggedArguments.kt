package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.Instantiation
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.di.compiler.tagged
import io.kmpkt.di.compiler.transform

/**
 * Replaces each [Argument.Tagged] by the list of services carrying that tag, highest priority first.
 *
 * Runs after [ResolveDecorators], so a decorated service is represented by its decorator.
 */
internal class ResolveTaggedArguments : BuilderPass {
    override fun process(container: ContainerBuilder) {
        container.definitions.values.toList().forEach { definition ->
            val parametersByName =
                if (definition.instantiation !is Instantiation.External &&
                    definition.instantiation !is Instantiation.Parent &&
                    definition.instantiation !is Instantiation.ContextScope
                ) {
                    container.types
                        .parameters(definition.type, definition.instantiation)
                        ?.associateBy { it.name }
                        .orEmpty()
                } else {
                    emptyMap()
                }

            val resolved =
                definition.arguments.mapValues { (paramName, argument) ->
                    val parameterType = parametersByName[paramName]?.type?.nonNull()
                    argument.transform {
                        if (it is Argument.Tagged) resolve(container, definition.id, it, parameterType) else it
                    }
                }

            if (resolved != definition.arguments) {
                container.update(definition.id) { it.copy(arguments = resolved) }
            }
        }
    }

    private fun resolve(
        container: ContainerBuilder,
        consumer: ServiceId,
        tagged: Argument.Tagged,
        parameterType: TypeRef?,
    ): Argument {
        val deferred = parameterType?.let(::deferredKind)
        val listType = if (deferred == null) parameterType else (parameterType.arguments.single() as TypeArgument.Of).type.nonNull()
        val elementType = (listType?.arguments?.singleOrNull() as? TypeArgument.Of)?.type

        val sequence =
            Argument.Sequence(
                items =
                    container
                        .tagged(tagged.tag)
                        .filterNot { tagged.excludeSelf && it.definition.id == consumer }
                        .map { Argument.Service(ServiceReference.ById(it.definition.id)) },
                elementType = elementType,
            )

        return if (deferred != null) Argument.Deferred(sequence, deferred) else sequence
    }

    private fun deferredKind(type: TypeRef): Argument.Deferred.Kind? =
        when {
            type.arguments.singleOrNull() !is TypeArgument.Of -> null
            type.qualifiedName == "kotlin.Lazy" -> Argument.Deferred.Kind.Lazy
            type.qualifiedName == "kotlin.Function0" -> Argument.Deferred.Kind.Provider
            else -> null
        }
}
