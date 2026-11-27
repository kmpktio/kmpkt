package io.kmpkt.di.compiler

/**
 * Answers what a [TypeRef] alone cannot; implemented over KSP by the processor and by a fake in tests.
 */
public interface TypeOracle {
    /** Whether a value of [from] can be passed where [to] is expected. */
    public fun isAssignable(
        from: TypeRef,
        to: TypeRef,
    ): Boolean

    /**
     * The arguments [type] gives to its supertype [of]: for a `class Handler : DomainEventHandler<InvoicePaid>`,
     * `typeArguments(Handler, of = DomainEventHandler<*>)` is `[InvoicePaid]`. Empty when [of] is no supertype.
     */
    public fun typeArguments(
        type: TypeRef,
        of: TypeRef,
    ): List<TypeArgument>

    /**
     * The parameters [instantiation] has to be called with, or `null` when the generated container could not
     * make the call at all: the class or function is unknown, not public, abstract, or overloaded.
     */
    public fun parameters(
        type: TypeRef,
        instantiation: Instantiation,
    ): List<Parameter>?

    /**
     * Every class declared in exactly the package [packageName], wherever it comes from: the module being
     * compiled or one it depends on. Not the packages below it - a classpath can be asked for a package, but
     * not for which packages there are.
     */
    public fun classesIn(packageName: String): List<ClassInfo>

    /** Abstract `val` properties declared on [type] (including inherited ones), used to auto-expose `view<V>()` entry points. */
    public fun properties(type: TypeRef): List<Parameter> = emptyList()
}

/** A class as `discover` sees it: enough to decide whether it could be a service, and what it would be bound to. */
public data class ClassInfo(
    val type: TypeRef,
    /** Every interface it implements, inherited ones included. */
    val interfaces: List<TypeRef> = emptyList(),
    val traits: Set<ClassTrait> = emptySet(),
)

/** What rules a class out as a service that nobody registered by name. */
public enum class ClassTrait {
    Abstract,
    Interface,
    Enum,
    Sealed,
    Object,
    Data,
    Annotation,
    Throwable,

    /** Private, local or inner: the generated container could not construct it. */
    Unreachable,

    /** Has type parameters, so there is no one type to register it as. */
    Generic,
}

public data class Parameter(
    val name: String,
    val type: TypeRef,
    val hasDefault: Boolean = false,
)

/**
 * Collects problems instead of stopping at the first, so that one compilation reports every unresolvable
 * parameter rather than one per attempt.
 */
public interface Diagnostics {
    public val hasErrors: Boolean

    public fun error(message: String)

    public fun warn(message: String)
}

public class CollectingDiagnostics : Diagnostics {
    private val collectedErrors = mutableListOf<String>()
    private val collectedWarnings = mutableListOf<String>()

    public val errors: List<String> get() = collectedErrors
    public val warnings: List<String> get() = collectedWarnings

    override val hasErrors: Boolean get() = collectedErrors.isNotEmpty()

    override fun error(message: String) {
        collectedErrors += message
    }

    override fun warn(message: String) {
        collectedWarnings += message
    }
}
