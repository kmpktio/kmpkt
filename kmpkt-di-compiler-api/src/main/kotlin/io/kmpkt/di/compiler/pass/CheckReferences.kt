package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.uses

/**
 * The last word before anything is removed: every entry point resolves, every reference points at a
 * definition, and nothing needs itself in order to be built.
 *
 * A reference held through `Lazy` or a provider does not need its target at construction time and is not
 * counted as a cycle.
 */
internal class CheckReferences : BuilderPass {
    override fun process(container: ContainerBuilder) {
        container.entryPoints
            .filter { it.key !in container.aliases }
            .forEach {
                container.diagnostics.error(
                    "Entry point \"${it.name}\" asks for ${it.key}, but ${container.describeUnbound(it.key)}",
                )
            }

        container.definitions.values.forEach { definition ->
            definition
                .uses()
                .filterNot { container.has(it.id) }
                .forEach { container.diagnostics.error("${definition.describe()} references \"${it.id}\", which does not exist.") }
        }

        Cycles(container).report()
    }

    private class Cycles(
        private val container: ContainerBuilder,
    ) {
        private val done = mutableSetOf<ServiceId>()
        private val path = mutableListOf<ServiceId>()

        fun report() {
            container.definitions.keys.forEach(::visit)
        }

        private fun visit(id: ServiceId) {
            if (id in done) return

            val entered = path.indexOf(id)
            if (entered >= 0) {
                val cycle = (path.subList(entered, path.size) + id).joinToString(" -> ")
                container.diagnostics.error(
                    "Circular reference: $cycle. Break it by taking a Lazy<…> or a () -> … in one of them.",
                )
                return
            }

            path += id
            container.definitions[id]
                ?.uses()
                ?.filterNot { it.deferred }
                ?.forEach { visit(it.id) }
            path.removeLast()
            done += id
        }
    }
}
