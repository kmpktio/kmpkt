package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder

/**
 * Gives every autoconfigured definition the tags registered for a type it is assignable to.
 *
 * A decorator gets none: it inherits the tags of the service it wraps instead (see [ResolveDecorators]),
 * and tagging both would put one handler into a tagged list twice.
 */
internal class ApplyAutoconfiguration : BuilderPass {
    override fun process(container: ContainerBuilder) {
        container.definitions.values
            .filter { it.autoconfigured && it.decorates == null }
            .forEach { definition ->
                val inherited =
                    container.autoconfigurations
                        .filter { container.types.isAssignable(definition.type, it.type) }
                        .flatMap { it.tags }
                        .filter { it !in definition.tags }
                        .distinct()

                if (inherited.isNotEmpty()) {
                    container.update(definition.id) { it.copy(tags = it.tags + inherited) }
                }
            }
    }
}
