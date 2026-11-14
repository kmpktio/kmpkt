package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.TypeRef

/**
 * Gives each argument that was passed by type to the one parameter that can take it.
 *
 * Runs before anything else looks at arguments, so that a parameter filled this way counts as given for
 * decoration and autowiring alike. A type that fits no free parameter, or more than one, is an error: the
 * point of passing by type is that there is nothing to choose.
 */
internal class BindTypedArguments : BuilderPass {
    override fun process(container: ContainerBuilder) {
        container.definitions.values
            .filter { it.typedArguments.isNotEmpty() }
            .forEach { bind(container, it) }
    }

    private fun bind(
        container: ContainerBuilder,
        definition: ServiceDefinition,
    ) {
        // An unconstructible service is Autowire's to report, with the reason.
        val parameters = container.types.parameters(definition.type, definition.instantiation) ?: return
        val arguments = definition.arguments.toMutableMap()

        definition.typedArguments.forEach { typed ->
            val candidates =
                parameters.filter { it.name !in arguments && fits(container, typed.type, it.type.nonNull()) }

            when (candidates.size) {
                1 -> arguments[candidates.single().name] = typed.argument
                0 ->
                    container.diagnostics.error(
                        "${definition.describe()} was given an argument of type ${typed.type}, " +
                            "but has no parameter left that takes one.",
                    )
                else ->
                    container.diagnostics.error(
                        "${definition.describe()} was given an argument of type ${typed.type}, which fits " +
                            "${candidates.size} parameters (${candidates.joinToString { it.name }}). Name the one that is meant.",
                    )
            }
        }

        container.update(definition.id) { it.copy(arguments = arguments, typedArguments = emptyList()) }
    }

    /**
     * The same type by name is a fit without asking: a [TypeRef] does not carry variance, so the oracle
     * would turn down `Map<KClass<Message>, …>` for a parameter declared `Map<KClass<out Message>, …>`,
     * which is the very type the argument was written against.
     */
    private fun fits(
        container: ContainerBuilder,
        argument: TypeRef,
        parameter: TypeRef,
    ): Boolean = argument == parameter || container.types.isAssignable(argument, parameter)
}
