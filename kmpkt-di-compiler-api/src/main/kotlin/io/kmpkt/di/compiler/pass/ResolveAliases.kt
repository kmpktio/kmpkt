package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.OnMissing
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.transform

/**
 * Turns every reference by key into a reference by id, so that from here on the definitions form a graph.
 */
internal class ResolveAliases : BuilderPass {
    override fun process(container: ContainerBuilder) {
        container.definitions.values.toList().forEach { definition ->
            val resolved = definition.arguments.mapValues { (_, argument) -> argument.transform { resolve(container, definition, it) } }

            if (resolved != definition.arguments) {
                container.update(definition.id) { it.copy(arguments = resolved) }
            }
        }
    }

    private fun resolve(
        container: ContainerBuilder,
        definition: ServiceDefinition,
        argument: Argument,
    ): Argument {
        val reference =
            when (argument) {
                is Argument.Service -> argument.reference
                is Argument.Deferred -> argument.reference
                else -> return argument
            } as? ServiceReference.ByKey ?: return argument
        val target = container.aliases[reference.key]

        return when {
            target == null && reference.onMissing == OnMissing.Null -> Argument.Literal(ConstValue.Null)
            target == null -> {
                container.diagnostics.error(
                    "${definition.describe()} references ${reference.key}, but ${container.describeUnbound(reference.key)}",
                )
                argument
            }
            argument is Argument.Deferred -> Argument.Deferred(reference = ServiceReference.ById(target), kind = argument.kind)
            else -> Argument.Service(ServiceReference.ById(target))
        }
    }
}
