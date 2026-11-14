package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.Instantiation
import io.kmpkt.di.compiler.Parameter
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.TypeRef

/**
 * Fills in every argument that was not given.
 *
 * The named binding `(type, parameter name)` is tried before the plain one. A parameter of `Lazy<T>` or
 * `() -> T` is resolved as `T` and deferred. What cannot be resolved falls back to the parameter's default,
 * then to `null` for a nullable one, and is an error otherwise.
 */
internal class Autowire : BuilderPass {
    override fun process(container: ContainerBuilder) {
        container.definitions.values
            .filter { it.instantiation !is Instantiation.External }
            .forEach { definition ->
                val parameters = container.types.parameters(definition.type, definition.instantiation)

                if (parameters == null) {
                    report(container, definition, "Cannot build ${definition.describe()}: ${unbuildable(definition)}")
                } else {
                    wire(container, definition, parameters)
                }
            }
    }

    private fun wire(
        container: ContainerBuilder,
        definition: ServiceDefinition,
        parameters: List<Parameter>,
    ) {
        (definition.arguments.keys - parameters.map { it.name }.toSet()).forEach { unknown ->
            report(
                container,
                definition,
                "${definition.describe()} was given an argument \"$unknown\", but has no such parameter. " +
                    "It takes: ${parameters.joinToString { it.name }.ifEmpty { "nothing" }}.",
            )
        }

        val resolved =
            parameters
                .filter { it.name !in definition.arguments }
                .mapNotNull { parameter -> resolve(container, definition, parameter)?.let { parameter.name to it } }

        if (resolved.isNotEmpty()) {
            container.update(definition.id) { it.copy(arguments = it.arguments + resolved) }
        }
    }

    private fun resolve(
        container: ContainerBuilder,
        definition: ServiceDefinition,
        parameter: Parameter,
    ): Argument? {
        val type = parameter.type.nonNull()
        val deferred = deferredKind(type)
        val wanted = if (deferred == null) type else (type.arguments.single() as TypeArgument.Of).type.nonNull()
        val key = if (definition.autowired) bound(container, wanted, parameter.name) else null

        return when {
            key != null && deferred != null -> Argument.Deferred(ServiceReference.ByKey(key), deferred)
            key != null -> Argument.Service(ServiceReference.ByKey(key))
            parameter.hasDefault -> Argument.Default
            parameter.type.nullable -> Argument.Literal(ConstValue.Null)
            else -> {
                report(container, definition, unresolvable(container, definition, parameter, wanted))
                null
            }
        }
    }

    /**
     * What is wrong with a service somebody registered is wrong now. What is wrong with a discovered one is
     * noted on it and only matters if it turns out to be needed - see [ReportDefinitionErrors].
     */
    private fun report(
        container: ContainerBuilder,
        definition: ServiceDefinition,
        message: String,
    ) {
        if (definition.discovered) {
            container.update(definition.id) { it.copy(errors = it.errors + message) }
        } else {
            container.diagnostics.error(message)
        }
    }

    private fun bound(
        container: ContainerBuilder,
        type: TypeRef,
        name: String,
    ): ServiceKey? = listOf(ServiceKey(type, name), ServiceKey(type)).firstOrNull { it in container.aliases }

    private fun deferredKind(type: TypeRef): Argument.Deferred.Kind? =
        when {
            type.arguments.singleOrNull() !is TypeArgument.Of -> null
            type.qualifiedName == "kotlin.Lazy" -> Argument.Deferred.Kind.Lazy
            type.qualifiedName == "kotlin.Function0" -> Argument.Deferred.Kind.Provider
            else -> null
        }

    private fun unresolvable(
        container: ContainerBuilder,
        definition: ServiceDefinition,
        parameter: Parameter,
        wanted: TypeRef,
    ): String {
        val subject = "parameter \"${parameter.name}\" of ${definition.describe()}"
        if (!definition.autowired) {
            return "No argument for $subject, and autowiring is off for it."
        }

        val key = ServiceKey(wanted)
        if (container.candidatesFor(key).isNotEmpty()) {
            return "Cannot autowire $subject: ${container.describeUnbound(key)}"
        }

        val almost =
            container.definitions.values
                .filter { it.id != definition.id && container.types.isAssignable(it.type, wanted) }
                .joinToString { it.describe() }
        val hint = if (almost.isEmpty()) "" else " Of that type, but not bound to it: $almost. Bind one, or pass the argument."

        return "Cannot autowire $subject: no service is bound to $wanted.$hint"
    }

    private fun unbuildable(definition: ServiceDefinition): String =
        when (val instantiation = definition.instantiation) {
            is Instantiation.Constructor ->
                "${definition.type} has no public constructor the generated container could call. " +
                    "Make the class and its primary constructor public, or register a public factory function instead."
            is Instantiation.Function ->
                "there is no single public function ${instantiation.packageName}.${instantiation.name} returning ${definition.type}."
            is Instantiation.ObjectFunction ->
                "there is no single public function ${instantiation.name} on the object ${instantiation.owner}."
            is Instantiation.StaticFactory ->
                "there is no single public static method ${instantiation.name} on ${instantiation.owner} returning ${definition.type}."
            is Instantiation.External -> "it is external."
        }
}
