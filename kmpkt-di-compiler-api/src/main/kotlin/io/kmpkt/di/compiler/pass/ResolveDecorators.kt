package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.Decoration
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.flatten
import io.kmpkt.di.compiler.transform

/**
 * Puts each decorator in front of the service it decorates.
 *
 * Decorators are taken highest priority first, each one receives whatever the decorated key resolves to at
 * that moment as its inner service, and then takes the key over - so the last one taken, the lowest
 * priority, is what consumers get. The wrapped service keeps its definition but hands its tags to the
 * decorator, which now stands in for it.
 */
internal class ResolveDecorators : BuilderPass {
    override fun process(container: ContainerBuilder) {
        container.definitions.values
            .filter { it.decorates != null }
            // Stable: decorators of equal priority wrap in registration order.
            .sortedByDescending { it.decorates?.priority }
            .forEach { decorate(container, it.id) }

        container.definitions.values
            .filter { definition -> definition.arguments.values.any { Argument.Inner in it.flatten() } }
            .forEach { container.diagnostics.error("${it.describe()} takes an inner service but decorates nothing.") }
    }

    private fun decorate(
        container: ContainerBuilder,
        id: ServiceId,
    ) {
        val decorator = container.definitions.getValue(id)
        val decoration = decorator.decorates ?: return
        val inner = container.find(decoration.target) ?: container.resolveParent(decoration.target)?.let { container.definitions[it] }

        if (inner == null) {
            container.diagnostics.error("${decorator.describe()} decorates ${decoration.target}, but no service is bound to that.")
            return
        }
        if (inner.id == id) {
            container.diagnostics.error(
                "${decorator.describe()} decorates ${decoration.target} and is bound to it as well, so it would wrap itself. " +
                    "Drop the bind: decorating takes the key over.",
            )
            return
        }

        val arguments = argumentsWithInner(container, decorator, decoration, inner.id) ?: return

        container.update(inner.id) { it.copy(tags = emptyList()) }
        container.update(id) { it.copy(decorates = null, arguments = arguments, tags = inner.tags + it.tags) }
        container.alias(decoration.target, id)
    }

    private fun argumentsWithInner(
        container: ContainerBuilder,
        decorator: ServiceDefinition,
        decoration: Decoration,
        inner: ServiceId,
    ): Map<String, Argument>? {
        val reference = Argument.Service(ServiceReference.ById(inner))

        if (decorator.arguments.values.any { Argument.Inner in it.flatten() }) {
            return decorator.arguments.mapValues { (_, argument) -> argument.transform { if (it == Argument.Inner) reference else it } }
        }

        // An unconstructible decorator is Autowire's to report, with the reason.
        val parameters = container.types.parameters(decorator.type, decorator.instantiation) ?: return decorator.arguments
        val candidates = parameters.filter { it.name !in decorator.arguments && it.type.nonNull() == decoration.target.type }

        return when (candidates.size) {
            1 -> decorator.arguments + (candidates.single().name to reference)
            0 -> {
                container.diagnostics.error(
                    "${decorator.describe()} decorates ${decoration.target} but has no parameter of that type to receive it.",
                )
                null
            }
            else -> {
                container.diagnostics.error(
                    "${decorator.describe()} decorates ${decoration.target} and has ${candidates.size} parameters of that type " +
                        "(${candidates.joinToString { it.name }}). Say which one receives it: argument(name, Argument.Inner).",
                )
                null
            }
        }
    }
}
