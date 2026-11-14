package io.kmpkt.di.compiler

import kotlin.reflect.KClass

/** What is passed for one parameter. */
public sealed interface Argument {
    public data class Service(
        val reference: ServiceReference,
    ) : Argument

    /** `Lazy<T>` or `() -> T`. The only edges a reference cycle may pass through. */
    public data class Deferred(
        val value: Argument,
        val kind: Kind,
    ) : Argument {
        public constructor(reference: ServiceReference, kind: Kind) : this(Service(reference), kind)

        public val reference: ServiceReference?
            get() = (value as? Service)?.reference

        public enum class Kind { Lazy, Provider }
    }

    /** Every service carrying [tag], as a list. Unresolved until `ResolveTaggedArguments` makes it a [Sequence]. */
    public data class Tagged(
        val tag: KClass<out Tag>,
        /** Leaves the consuming service out of its own list. */
        val excludeSelf: Boolean = true,
    ) : Argument

    /** `listOf(…)` */
    public data class Sequence(
        val items: List<Argument>,
        val elementType: TypeRef? = null,
    ) : Argument

    /** `mapOf(… to …)` */
    public data class Dictionary(
        val entries: List<Pair<Argument, Argument>>,
    ) : Argument

    public data class Literal(
        val value: ConstValue,
    ) : Argument

    /** Reads environment variable [name] at runtime, falling back to [default] or failing when unset. */
    public data class Environment(
        val name: String,
        val default: ConstValue? = null,
    ) : Argument

    /** The decorated service, inside a decorator. */
    public data object Inner : Argument

    /** Leaves the parameter out, so its Kotlin default applies. */
    public data object Default : Argument
}

public sealed interface ServiceReference {
    public val onMissing: OnMissing

    public data class ById(
        val id: ServiceId,
        override val onMissing: OnMissing = OnMissing.Fail,
    ) : ServiceReference

    public data class ByKey(
        val key: ServiceKey,
        override val onMissing: OnMissing = OnMissing.Fail,
    ) : ServiceReference
}

/** What happens when a referenced service is missing: a compile error, or `null`. */
public enum class OnMissing { Fail, Null }

/** A value the generator can write back out as source. */
public sealed interface ConstValue {
    public data object Null : ConstValue

    public data class Text(
        val value: String,
    ) : ConstValue

    public data class Number(
        val value: kotlin.Number,
    ) : ConstValue

    public data class Flag(
        val value: Boolean,
    ) : ConstValue

    /** `Type.ENTRY` */
    public data class EnumEntry(
        val type: TypeRef,
        val entry: String,
    ) : ConstValue

    /** A `kotlin.time.Duration`, written out as the ISO string it parses back from. */
    public data class Duration(
        val value: kotlin.time.Duration,
    ) : ConstValue

    /** `Type::class` - a class literal, not reflection. */
    public data class ClassLiteral(
        val type: TypeRef,
    ) : ConstValue
}

/** A reference to whatever is bound to [T], optionally under [qualifier]. */
public inline fun <reified T : Any> service(qualifier: String? = null): Argument.Service =
    Argument.Service(
        ServiceReference.ByKey(
            ServiceKey(typeRef<T>(), qualifier),
        ),
    )

/** Every service tagged [T], highest priority first. */
public inline fun <reified T : Tag> tagged(excludeSelf: Boolean = true): Argument = Argument.Tagged(T::class, excludeSelf)

public fun literal(value: String): Argument = Argument.Literal(ConstValue.Text(value))

/** Reads environment variable [name] at runtime, falling back to [default] or failing when unset. */
public fun env(
    name: String,
    default: String? = null,
): Argument = Argument.Environment(name, default?.let(ConstValue::Text))

/** Reads integer environment variable [name] at runtime, falling back to [default]. */
public fun env(
    name: String,
    default: Int,
): Argument = Argument.Environment(name, ConstValue.Number(default))

public fun literal(value: Number): Argument = Argument.Literal(ConstValue.Number(value))

public fun literal(value: Boolean): Argument = Argument.Literal(ConstValue.Flag(value))

public fun literal(value: kotlin.time.Duration): Argument = Argument.Literal(ConstValue.Duration(value))

public fun literal(value: Enum<*>): Argument =
    Argument.Literal(
        ConstValue.EnumEntry(
            TypeRef(value.declaringJavaClass.kotlin.qualifiedName ?: value.declaringJavaClass.name),
            value.name,
        ),
    )

/** `T::class` in the generated code. */
public inline fun <reified T : Any> classLiteral(): Argument = Argument.Literal(ConstValue.ClassLiteral(typeRef<T>()))

/** Rebuilds an argument bottom-up: nested ones first, then the one that holds them. */
internal fun Argument.transform(change: (Argument) -> Argument): Argument =
    change(
        when (this) {
            is Argument.Deferred -> Argument.Deferred(value.transform(change), kind)
            is Argument.Sequence -> Argument.Sequence(items.map { it.transform(change) }, elementType)
            is Argument.Dictionary -> Argument.Dictionary(entries.map { (key, value) -> key.transform(change) to value.transform(change) })
            else -> this
        },
    )

/** Every argument in this one, itself included. */
internal fun Argument.flatten(): List<Argument> =
    when (this) {
        is Argument.Deferred -> listOf(this) + value.flatten()
        is Argument.Sequence -> listOf(this) + items.flatMap { it.flatten() }
        is Argument.Dictionary -> listOf(this) + entries.flatMap { (key, value) -> key.flatten() + value.flatten() }
        else -> listOf(this)
    }

/** A reference a definition holds, and whether it is held through `Lazy` or a provider. */
internal data class Use(
    val id: ServiceId,
    val deferred: Boolean,
)

/** The resolved references of a definition. `ByKey` references are skipped: before `ResolveAliases` there are no edges yet. */
internal fun ServiceDefinition.uses(): List<Use> = arguments.values.flatMap { it.uses(deferred = false) }

private fun Argument.uses(deferred: Boolean): List<Use> =
    when (this) {
        is Argument.Service -> listOfNotNull((reference as? ServiceReference.ById)?.let { Use(it.id, deferred) })
        is Argument.Deferred -> value.uses(deferred = true)
        is Argument.Sequence -> items.flatMap { it.uses(deferred) }
        is Argument.Dictionary -> entries.flatMap { (key, value) -> key.uses(deferred) + value.uses(deferred) }
        else -> emptyList()
    }
