package io.kmpkt.di.compiler

/**
 * Identity of one definition. Defaults to the name of the type it produces; given explicitly where one type
 * is registered twice.
 *
 * Here the id only tells definitions apart - a consumer asks by [ServiceKey].
 */
@JvmInline
public value class ServiceId(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        public inline fun <reified T : Any> of(): ServiceId = ServiceId(typeRef<T>().toString())
    }
}

/**
 * What a consumer asks for: a type, narrowed by a name where the type alone is ambiguous.
 */
public data class ServiceKey(
    val type: TypeRef,
    val qualifier: String? = null,
) {
    override fun toString(): String = if (qualifier == null) type.toString() else "$type named \"$qualifier\""
}

public data class ServiceDefinition(
    val id: ServiceId,
    /** The type the generated property is declared with: the class for a constructor, the return type for a function. */
    val type: TypeRef,
    val instantiation: Instantiation = Instantiation.Constructor,
    /** By parameter name. A parameter that is absent here is autowired. */
    val arguments: Map<String, Argument> = emptyMap(),
    /** Arguments given for "the parameter of this type". `BindTypedArguments` moves each one into [arguments]. */
    val typedArguments: List<TypedArgument> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val scope: Scope = Scope.Shared,
    /** When false (default), shared services are initialized eagerly on container creation. */
    val lazy: Boolean = false,
    val decorates: Decoration? = null,
    val autowired: Boolean = true,
    val autoconfigured: Boolean = true,
    /** Set by `InlineSingleUse`: built inside its only consumer instead of getting a property of its own. */
    val inlined: Boolean = false,
    /** Who registered it - a component's class name - for error messages. */
    val source: String? = null,
    /**
     * Found by `discover` rather than registered by name. Nobody vouched for such a class being a service,
     * so what is wrong with it is kept in [errors] and only reported if something ends up needing it.
     */
    val discovered: Boolean = false,
    /** Why this definition cannot be built. */
    val errors: List<String> = emptyList(),
) {
    /** How an error message names this service. */
    public fun describe(): String = if (source == null) "\"$id\"" else "\"$id\" (registered by $source)"
}

/** An argument for whichever single parameter a value of [type] can be passed to. */
public data class TypedArgument(
    val type: TypeRef,
    val argument: Argument,
)

public sealed interface Instantiation {
    /** The primary constructor of the definition's type. */
    public data object Constructor : Instantiation

    /** A top-level factory function. */
    public data class Function(
        val packageName: String,
        val name: String,
    ) : Instantiation

    /** A function of an `object`. */
    public data class ObjectFunction(
        val owner: TypeRef,
        val name: String,
    ) : Instantiation

    /** A static method of a Java class: `Clock.systemUTC()`. */
    public data class StaticFactory(
        val owner: TypeRef,
        val name: String,
    ) : Instantiation

    /**
     * Not built by the container but handed to its constructor as [parameterName].
     *
     * This is where runtime configuration enters, lambdas included: the compile-time pipeline only ever
     * sees that such a value will exist.
     */
    public data class External(
        val parameterName: String,
    ) : Instantiation
}

public enum class Scope {
    /** One instance per container. */
    Shared,

    /** A new instance for every consumer. */
    Prototype,
}

/**
 * Makes a definition wrap whatever [target] resolves to. Among several decorators of one target, the highest
 * [priority] is applied first and so ends up innermost.
 */
public data class Decoration(
    val target: ServiceKey,
    val priority: Int = 0,
)

/**
 * A marker on a definition that passes can query. Tags are plain classes of the `-di` module with typed
 * properties.
 *
 * ```kotlin
 * data class EventHandlerTag(override val priority: Int = 0) : Tag
 * ```
 */
public interface Tag {
    /** Orders tagged services: highest first, registration order among equals. */
    public val priority: Int get() = 0
}

/** Applied to every `io.kmpkt.di.ApplicationRunner` so `RemoveUnused` keeps it and `KmpKt.run` starts it. */
public data class ApplicationRunnerTag(
    override val priority: Int = 0,
) : Tag
