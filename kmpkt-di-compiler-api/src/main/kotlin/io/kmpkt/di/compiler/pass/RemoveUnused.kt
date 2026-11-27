package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.ApplicationRunnerTag
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.uses

/**
 * Removes every definition that no entry point or [ApplicationRunnerTag] reaches.
 *
 * Entry points and `ApplicationRunner` services are the roots; a deferred
 * reference keeps its target alive like any other.
 */
internal class RemoveUnused : BuilderPass {
    override fun process(container: ContainerBuilder) {
        val reached = mutableSetOf<ServiceId>()
        val pending =
            ArrayDeque(
                container.entryPoints.mapNotNull { container.aliases[it.key] } +
                    container.protectedIds +
                    container.definitions.values
                        .filter { it.tags.any { tag -> tag is ApplicationRunnerTag } }
                        .map { it.id },
            )

        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (reached.add(id)) {
                pending +=
                    container.definitions[id]
                        ?.uses()
                        .orEmpty()
                        .map { it.id }
            }
        }

        (container.definitions.keys - reached).forEach(container::remove)
    }
}
