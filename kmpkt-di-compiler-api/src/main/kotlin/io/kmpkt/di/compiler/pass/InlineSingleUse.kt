package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.ApplicationRunnerTag
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.Instantiation
import io.kmpkt.di.compiler.Scope
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.Use
import io.kmpkt.di.compiler.uses

/**
 * Marks a service that exactly one other service uses as built inside that consumer.
 *
 * Inlines definitions that are shared, not an entry point, referenced once and not through a deferred edge,
 * by a consumer that is itself shared (inside a prototype it would be built again for every instance).
 */
internal class InlineSingleUse : BuilderPass {
    override fun process(container: ContainerBuilder) {
        val entryPoints = container.entryPoints.mapNotNull { container.aliases[it.key] }.toSet()
        val runners =
            container.definitions.values
                .filter { it.tags.any { tag -> tag is ApplicationRunnerTag } }
                .map { it.id }
                .toSet()
        val uses: Map<Use, List<ServiceDefinition>> =
            container.definitions.values
                .flatMap { consumer -> consumer.uses().map { it to consumer } }
                .groupBy({ it.first }, { it.second })
        val consumersOf = uses.entries.groupBy({ it.key.id }, { it })

        container.definitions.values
            .filter {
                it.scope == Scope.Shared &&
                    it.instantiation !is Instantiation.External &&
                    it.id !in entryPoints &&
                    it.id !in runners
            }.forEach { definition ->
                val only = consumersOf[definition.id]?.singleOrNull() ?: return@forEach
                val consumer = only.value.singleOrNull() ?: return@forEach

                if (!only.key.deferred && consumer.scope == Scope.Shared) {
                    container.update(definition.id) { it.copy(inlined = true) }
                }
            }
    }
}
