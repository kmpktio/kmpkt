package io.kmpkt.di.compiler

import io.kmpkt.di.compiler.pass.ApplyAutoconfiguration
import io.kmpkt.di.compiler.pass.Autowire
import io.kmpkt.di.compiler.pass.BindTypedArguments
import io.kmpkt.di.compiler.pass.CheckReferences
import io.kmpkt.di.compiler.pass.InlineSingleUse
import io.kmpkt.di.compiler.pass.RemoveUnused
import io.kmpkt.di.compiler.pass.ReportDefinitionErrors
import io.kmpkt.di.compiler.pass.ResolveAliases
import io.kmpkt.di.compiler.pass.ResolveDecorators
import io.kmpkt.di.compiler.pass.ResolveTaggedArguments

/**
 * Runs the pipeline over a [ContainerBuilder]:
 *
 * 1. every component's `prepare()`, then every [Configuration]
 * 2. each `register(config)` into a builder of its own that is merged afterwards;
 *    what the application registered in step 1 is applied last and wins
 * 3. the passes, phase by phase
 *
 * Within a step, a component runs after the ones it requires. Plain Kotlin over the model: nothing here
 * knows about KSP.
 */
public class ContainerCompiler(
    private val components: List<Component<*>> = emptyList(),
    private val configurations: List<Configuration<*>> = emptyList(),
) {
    /** The definitions to generate, or `null` when [ContainerBuilder.diagnostics] holds the reasons there are none. */
    public fun compile(container: ContainerBuilder): CompiledContainer? {
        val ordered = inOrderOfRequirements(container) ?: return null

        builtIn.forEach { container.addPass(it.pass, it.phase, it.priority) }
        container.autoconfigure(TypeRef("io.kmpkt.di.ApplicationRunner")) { tag(ApplicationRunnerTag()) }
        ordered.forEach { with(it) { container.prepare() } }
        configurations.forEach { with(it) { container.configure() } }
        load(ordered, container)

        // Stable, so passes of one phase and priority keep the order they were added in.
        val passes = container.passes.sortedWith(compareBy<RegisteredPass> { it.phase.order }.thenByDescending { it.priority })
        for (registered in passes) {
            if (container.diagnostics.hasErrors) return null
            registered.pass.process(container)
        }
        if (container.diagnostics.hasErrors) return null

        return CompiledContainer(
            definitions = container.definitions.values.toList(),
            entryPoints =
                container.entryPoints.map { entryPoint ->
                    ResolvedEntryPoint(entryPoint.name, entryPoint.key.type, container.aliases.getValue(entryPoint.key))
                },
            runners =
                container.definitions.values
                    .filter { it.tags.any { tag -> tag is ApplicationRunnerTag } }
                    .sortedByDescending { definition ->
                        definition.tags.filterIsInstance<ApplicationRunnerTag>().maxOf { it.priority }
                    }.map { it.id },
        )
    }

    /**
     * The components, each after the ones it requires and otherwise in the order given - or `null`, with
     * the reason reported, when one requires a component that is not there or two require each other.
     */
    private fun inOrderOfRequirements(container: ContainerBuilder): List<Component<*>>? {
        val byClass = components.associateBy { it::class }

        components.forEach { component ->
            (component.requires - byClass.keys).forEach { missing ->
                container.diagnostics.error(
                    "${component::class.qualifiedName} requires ${missing.qualifiedName}, which is not among the components. " +
                        "Put the module that declares it on the processor's classpath - ksp(project(\":…-di\")).",
                )
            }
        }
        if (container.diagnostics.hasErrors) return null

        val ordered = LinkedHashSet<Component<*>>()
        val visiting = mutableListOf<Component<*>>()

        fun place(component: Component<*>): Boolean {
            if (component in ordered) return true
            if (component in visiting) {
                val cycle = (visiting.dropWhile { it != component } + component).joinToString(" -> ") { it::class.qualifiedName.toString() }
                container.diagnostics.error("Components require each other in a circle: $cycle.")
                return false
            }

            visiting += component
            val placed = component.requires.all { place(byClass.getValue(it)) }
            visiting.removeLast()

            return placed.also { if (it) ordered += component }
        }

        return if (components.all(::place)) ordered.toList() else null
    }

    private fun load(
        ordered: List<Component<*>>,
        container: ContainerBuilder,
    ) {
        val ownDefinitions = container.definitions.toMap()
        // Only what was bound by name: what discovery inferred must not override a component's binding.
        val ownAliases = container.explicitAliases

        ordered.forEach { load(it, container) }

        ownDefinitions.values.forEach { container.service(it) }
        ownAliases.forEach { (key, target) -> container.alias(key, target) }
    }

    private fun <C : Any> load(
        component: Component<C>,
        container: ContainerBuilder,
    ) {
        val options = component.defaults()
        container.optionsFor(component::class).forEach { block -> block(options) }

        val scratch = container.scratch(component::class.qualifiedName)
        with(component) { scratch.register(options) }
        container.merge(scratch)
    }

    private companion object {
        val builtIn =
            listOf(
                RegisteredPass(ApplyAutoconfiguration(), PassPhase.BeforeOptimization, priority = 100),
                RegisteredPass(BindTypedArguments(), PassPhase.Optimization, priority = 0),
                RegisteredPass(ResolveDecorators(), PassPhase.Optimization, priority = 0),
                RegisteredPass(Autowire(), PassPhase.Optimization, priority = 0),
                RegisteredPass(ResolveTaggedArguments(), PassPhase.Optimization, priority = 0),
                RegisteredPass(ResolveAliases(), PassPhase.Optimization, priority = 0),
                RegisteredPass(CheckReferences(), PassPhase.Optimization, priority = 0),
                RegisteredPass(RemoveUnused(), PassPhase.Removing, priority = 0),
                RegisteredPass(ReportDefinitionErrors(), PassPhase.Removing, priority = 0),
                RegisteredPass(InlineSingleUse(), PassPhase.Removing, priority = 0),
            )
    }
}

/** What is left after compilation: exactly what the generator writes, in registration order. */
public data class CompiledContainer(
    val definitions: List<ServiceDefinition>,
    val entryPoints: List<ResolvedEntryPoint>,
    val runners: List<ServiceId> = emptyList(),
) {
    public fun definition(id: ServiceId): ServiceDefinition = definitions.first { it.id == id }
}

public data class ResolvedEntryPoint(
    val name: String,
    val type: TypeRef,
    val id: ServiceId,
)
