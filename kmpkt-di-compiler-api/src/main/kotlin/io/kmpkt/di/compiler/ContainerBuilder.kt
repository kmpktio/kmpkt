package io.kmpkt.di.compiler

import kotlin.jvm.internal.CallableReference
import kotlin.jvm.internal.ClassBasedDeclarationContainer
import kotlin.reflect.KClass
import kotlin.reflect.KFunction

/**
 * The container while it is being compiled: definitions, the keys they are bound to, and what the pipeline
 * was asked to do with them. Components fill it, passes rewrite it, the generator reads what is left.
 *
 * ```kotlin
 * container.service<JdbcOutboxMessageBus> {
 *     bind<MessageBus>()
 * }
 * container.external<RetryConfig>()
 * container.service<RetryingMessageBus> {
 *     decorates<MessageBus>(priority = 10)
 * }
 * ```
 *
 * Constructor parameters are autowired unless given: by the key `(type, parameter name)` first, then by the
 * type alone. A service is found under its own type and under whatever it was explicitly bound to - not
 * under every supertype, avoiding ambiguous-type errors.
 */
@ContainerDsl
public class ContainerBuilder private constructor(
    public val types: TypeOracle,
    public val diagnostics: Diagnostics,
    private val pipeline: Pipeline,
    private val source: String?,
) {
    public constructor(
        types: TypeOracle,
        diagnostics: Diagnostics,
    ) : this(types, diagnostics, Pipeline(), null)

    private val mutableDefinitions = LinkedHashMap<ServiceId, ServiceDefinition>()
    private val mutableAliases = LinkedHashMap<ServiceKey, ServiceId>()

    /** Keys that were bound by discovery, not by anybody saying so. An explicit binding replaces them silently. */
    private val weakKeys = mutableSetOf<ServiceKey>()

    /** Keys several discovered services could stand for, and which. Bound to none of them until somebody chooses. */
    private val ambiguousKeys = LinkedHashMap<ServiceKey, List<ServiceId>>()

    public val definitions: Map<ServiceId, ServiceDefinition> get() = mutableDefinitions

    /** Which definition a key resolves to. Binding a key again replaces the earlier binding. */
    public val aliases: Map<ServiceKey, ServiceId> get() = mutableAliases

    public val entryPoints: List<EntryPoint> get() = pipeline.entryPoints

    /** Adds [definition], replacing one of the same id, and binds it to its own type. */
    public fun service(definition: ServiceDefinition): DefinitionConfigurator {
        mutableDefinitions[definition.id] = if (definition.source == null) definition.copy(source = source) else definition
        alias(ServiceKey(definition.type.nonNull()), definition.id)

        return DefinitionConfigurator(this, definition.id)
    }

    /** By [TypeRef], for a class the caller cannot name in code - `TypeRef.parse("com.acme.app.Thing")`. */
    public fun service(
        type: TypeRef,
        id: ServiceId = ServiceId(type.toString()),
        configure: DefinitionConfigurator.() -> Unit = {},
    ): DefinitionConfigurator = service(ServiceDefinition(id, type)).apply(configure)

    /** A service built through the primary constructor of [T], which has to be public. */
    public inline fun <reified T : Any> service(
        id: ServiceId = ServiceId.of<T>(),
        configure: DefinitionConfigurator.() -> Unit = {},
    ): DefinitionConfigurator = service(typeRef<T>(), id).apply(configure)

    public fun factory(
        type: TypeRef,
        instantiation: Instantiation,
        id: ServiceId = ServiceId(type.toString()),
        configure: DefinitionConfigurator.() -> Unit = {},
    ): DefinitionConfigurator = service(ServiceDefinition(id, type, instantiation)).apply(configure)

    /**
     * A service built by calling [function] — a top-level function, one of an `object`, or a static method
     * of a Java class such as `Clock::systemUTC` — registered as its return type. This is how a feature module
     * keeps an implementation `internal`:
     *
     * ```kotlin
     * container.factory(::createSecretAdapter)
     * ```
     */
    public inline fun <reified T : Any> factory(
        function: KFunction<T>,
        id: ServiceId = ServiceId.of<T>(),
        configure: DefinitionConfigurator.() -> Unit = {},
    ): DefinitionConfigurator = factory(typeRef<T>(), function.toInstantiation(), id).apply(configure)

    /** The same by name, for a function a reference cannot pick - an overloaded one. */
    public inline fun <reified T : Any> factory(
        packageName: String,
        name: String,
        id: ServiceId = ServiceId.of<T>(),
        configure: DefinitionConfigurator.() -> Unit = {},
    ): DefinitionConfigurator = factory(typeRef<T>(), Instantiation.Function(packageName, name), id).apply(configure)

    public fun external(
        type: TypeRef,
        name: String = type.qualifiedName.substringAfterLast('.').replaceFirstChar { it.lowercase() },
        id: ServiceId = ServiceId(type.toString()),
        configure: DefinitionConfigurator.() -> Unit = {},
    ): DefinitionConfigurator = service(ServiceDefinition(id, type, Instantiation.External(name))).apply(configure)

    /**
     * A service the application hands to the generated container's constructor, as the parameter [name] -
     * by default the type's name with a lower-case first letter. Runtime configuration enters here.
     */
    public inline fun <reified T : Any> external(
        name: String? = null,
        id: ServiceId = ServiceId.of<T>(),
        configure: DefinitionConfigurator.() -> Unit = {},
    ): DefinitionConfigurator =
        typeRef<T>()
            .let { type -> if (name == null) external(type, id = id) else external(type, name, id) }
            .apply(configure)

    public fun update(
        id: ServiceId,
        change: (ServiceDefinition) -> ServiceDefinition,
    ) {
        val definition = mutableDefinitions[id] ?: throw IllegalArgumentException("No service \"$id\" to update")
        mutableDefinitions[id] = change(definition).copy(id = id)
    }

    public inline fun <reified T : Any> update(noinline change: (ServiceDefinition) -> ServiceDefinition) {
        update(ServiceId.of<T>(), change)
    }

    /** Removes the definition and every binding to it. */
    public fun remove(id: ServiceId) {
        mutableDefinitions.remove(id)
        mutableAliases.values.removeAll { it == id }
        weakKeys.retainAll(mutableAliases.keys)

        // One candidate fewer may be what settles an ambiguity.
        ambiguousKeys.filterValues { id in it }.forEach { (key, candidates) ->
            ambiguousKeys.remove(key)
            (candidates - id).forEach { bindDiscovered(key, it) }
        }
    }

    public fun alias(
        key: ServiceKey,
        target: ServiceId,
    ) {
        mutableAliases[key] = target
        weakKeys.remove(key)
        ambiguousKeys.remove(key)
    }

    /**
     * The discovered services [key] could stand for when it is bound to none of them because there are
     * several - empty otherwise. Binding the key explicitly settles it.
     */
    public fun candidatesFor(key: ServiceKey): List<ServiceId> = ambiguousKeys[key].orEmpty()

    /** Why nothing can be injected for [key], for an error message. */
    public fun describeUnbound(key: ServiceKey): String {
        val candidates = candidatesFor(key)

        return if (candidates.isEmpty()) {
            "no service is bound to that."
        } else {
            "$key is implemented by ${candidates.size} discovered services (${candidates.joinToString()}) and bound to " +
                "none of them. Bind the one that is meant."
        }
    }

    /**
     * Registers classes of the package [packageName] as services, one package at a time:
     *
     * ```kotlin
     * container.discover("dev.michaelpetri.todo.application") {
     *     services<CommandHandler<*>>()
     *     services<EventHandler<*>>()
     * }
     * ```
     *
     * Left out without being asked: abstract and sealed classes, interfaces, enums, objects, annotations,
     * data classes, exceptions, and what the container could not construct. A class registered before by
     * name is left as it was registered.
     *
     * A discovered service is bound to its own type and to every interface it implements, so a port is
     * injected without a `bind`. Two discovered implementations of one interface bind it to neither, and
     * asking for it is an error that names both. An explicit binding always wins.
     *
     * When [DiscoveryBuilder.services] is called inside [configure], only subtypes of those types are
     * registered, and they are held to strict wiring (what cannot be wired fails compilation at once).
     * Without `services<…>()`, every constructible class in [packageName] is swept in lazily and only
     * checked if reachable from an entry point.
     */
    public fun discover(
        packageName: String,
        configure: DiscoveryBuilder.() -> Unit = {},
    ) {
        val builder = DiscoveryBuilder().apply(configure)
        val targets = builder.targets

        if (targets.isEmpty()) {
            discover(packageName, only = null, builder)
        } else {
            targets.forEach { target ->
                discover(packageName, only = target, builder)
            }
        }
    }

    private fun discover(
        packageName: String,
        only: TypeRef?,
        filter: DiscoveryBuilder,
    ) {
        val label = if (only == null) "discover(\"$packageName\")" else "discover(\"$packageName\") { services<$only>() }"

        types
            .classesIn(packageName)
            .filter { it.traits.isEmpty() && !filter.excludes(it.type) && !has(ServiceId(it.type.toString())) }
            .filter { only == null || types.isAssignable(it.type, only) }
            .forEach { discovered ->
                val id = ServiceId(discovered.type.toString())
                mutableDefinitions[id] = ServiceDefinition(id, discovered.type, source = source ?: label, discovered = only == null)
                bindDiscovered(ServiceKey(discovered.type), id)
                discovered.interfaces
                    .filterNot { it.qualifiedName.startsWith("kotlin.") || it.qualifiedName.startsWith("java.") }
                    .forEach { bindDiscovered(ServiceKey(it), id) }
            }
    }

    private fun bindDiscovered(
        key: ServiceKey,
        id: ServiceId,
    ) {
        val bound = mutableAliases[key]

        when {
            key in ambiguousKeys -> ambiguousKeys[key] = ambiguousKeys.getValue(key) + id
            bound == null -> {
                mutableAliases[key] = id
                weakKeys += key
            }
            key in weakKeys && bound != id -> {
                mutableAliases.remove(key)
                weakKeys.remove(key)
                ambiguousKeys[key] = listOf(bound, id)
            }
            // Bound by name: discovery does not argue with that.
        }
    }

    public fun has(id: ServiceId): Boolean = id in mutableDefinitions

    /** The definition [key] is bound to, if any. */
    public fun find(key: ServiceKey): ServiceDefinition? = mutableAliases[key]?.let(mutableDefinitions::get)

    public inline fun <reified T : Any> find(qualifier: String? = null): ServiceDefinition? = find(ServiceKey(typeRef<T>(), qualifier))

    /** Every definition carrying a [tag], highest [Tag.priority] first, registration order among equals. */
    public fun <T : Tag> tagged(tag: KClass<T>): List<TaggedService<T>> =
        mutableDefinitions.values
            .mapNotNull { definition ->
                definition.tags.firstOrNull { tag.isInstance(it) }?.let {
                    @Suppress("UNCHECKED_CAST")
                    TaggedService(definition, it as T)
                }
            }.sortedByDescending { it.tag.priority }

    public inline fun <reified T : Tag> tagged(): List<TaggedService<T>> = tagged(T::class)

    public fun autoconfigure(
        type: TypeRef,
        configure: AutoconfigureBuilder.() -> Unit,
    ) {
        pipeline.autoconfigurations += Autoconfiguration(type, AutoconfigureBuilder().apply(configure).tags)
    }

    /**
     * Applies to every service whose type is a [T], whoever registered it and whenever.
     *
     * ```kotlin
     * container.autoconfigure<DomainEventHandler<*>> {
     *     tag(EventHandlerTag())
     * }
     * ```
     */
    public inline fun <reified T : Any> autoconfigure(noinline configure: AutoconfigureBuilder.() -> Unit) {
        autoconfigure(typeRef<T>(), configure)
    }

    /** Among passes of one phase, the highest [priority] runs first, and equal ones in the order they were added. */
    public fun addPass(
        pass: BuilderPass,
        phase: PassPhase = PassPhase.BeforeOptimization,
        priority: Int = 0,
    ) {
        pipeline.passes += RegisteredPass(pass, phase, priority)
    }

    /**
     * Queues options for [component]; blocks are applied in the order they were queued. The component is
     * named by its class - the instance is the processor's to create - and the options type follows from it.
     *
     * ```kotlin
     * container.configure(MessengerComponent::class) {
     *     bus("command")
     * }
     * ```
     */
    public fun <C : Any> configure(
        component: KClass<out Component<C>>,
        block: C.() -> Unit,
    ) {
        pipeline.optionsOf(component).add(erased(block))
    }

    /** `configure<MessengerComponent, _> { … }` - the same, with the options type left to the compiler. */
    public inline fun <reified E : Component<C>, C : Any> configure(noinline block: C.() -> Unit) {
        configure(E::class, block)
    }

    /** Queues options ahead of everything queued so far. */
    public fun <C : Any> prepend(
        component: KClass<out Component<C>>,
        block: C.() -> Unit,
    ) {
        pipeline.optionsOf(component).add(0, erased(block))
    }

    public inline fun <reified E : Component<C>, C : Any> prepend(noinline block: C.() -> Unit) {
        prepend(E::class, block)
    }

    /** Declares a service the generated container exposes as [name]. Called by the processor per `@Container` property. */
    public fun entryPoint(
        name: String,
        key: ServiceKey,
    ) {
        pipeline.entryPoints += EntryPoint(name, key)
    }

    internal val passes: List<RegisteredPass> get() = pipeline.passes
    internal val autoconfigurations: List<Autoconfiguration> get() = pipeline.autoconfigurations

    internal fun optionsFor(component: KClass<*>): List<(Any) -> Unit> = pipeline.optionsOf(component)

    /** An empty builder over the same pipeline, for one component to load into. */
    internal fun scratch(source: String?): ContainerBuilder = ContainerBuilder(types, diagnostics, pipeline, source)

    /** The bindings somebody asked for, as opposed to the ones discovery inferred. */
    internal val explicitAliases: Map<ServiceKey, ServiceId> get() = mutableAliases.filterKeys { it !in weakKeys }

    /**
     * Takes over everything [other] holds; where both know an id or a key, [other]'s wins - except that what
     * [other] only inferred by discovery stays an inference here as well.
     */
    internal fun merge(other: ContainerBuilder) {
        mutableDefinitions.putAll(other.mutableDefinitions)
        other.explicitAliases.forEach { (key, target) -> alias(key, target) }
        other.mutableAliases.filterKeys { it in other.weakKeys }.forEach { (key, target) -> bindDiscovered(key, target) }
        other.ambiguousKeys.forEach { (key, candidates) -> candidates.forEach { bindDiscovered(key, it) } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <C : Any> erased(block: C.() -> Unit): (Any) -> Unit = block as (Any) -> Unit

    private class Pipeline {
        val passes = mutableListOf<RegisteredPass>()
        val autoconfigurations = mutableListOf<Autoconfiguration>()
        val entryPoints = mutableListOf<EntryPoint>()
        private val options = mutableMapOf<KClass<*>, MutableList<(Any) -> Unit>>()

        fun optionsOf(component: KClass<*>): MutableList<(Any) -> Unit> = options.getOrPut(component) { mutableListOf() }
    }
}

/** Keeps a registration block from reaching the builder around it by accident. */
@DslMarker
public annotation class ContainerDsl

/** The receiver of a registration block, and what `service` hands back. */
@ContainerDsl
public class DefinitionConfigurator internal constructor(
    private val container: ContainerBuilder,
    public val id: ServiceId,
) {
    public fun bind(
        type: TypeRef,
        qualifier: String? = null,
    ): DefinitionConfigurator = apply { container.alias(ServiceKey(type, qualifier), id) }

    /** Makes this the service consumers get when they ask for [T]. Replaces whoever was bound to it before. */
    public inline fun <reified T : Any> bind(qualifier: String? = null): DefinitionConfigurator = bind(typeRef<T>(), qualifier)

    /**
     * Binds the service's own type under [qualifier] as well. A parameter of that type whose name is
     * [qualifier] is autowired with this service rather than with the unqualified one.
     */
    public fun named(qualifier: String): DefinitionConfigurator = bind(container.definitions.getValue(id).type, qualifier)

    public fun tag(tag: Tag): DefinitionConfigurator = change { it.copy(tags = it.tags + tag) }

    public fun decorates(
        target: ServiceKey,
        priority: Int = 0,
    ): DefinitionConfigurator = change { it.copy(decorates = Decoration(target, priority)) }

    /**
     * Wraps whatever [T] resolves to. The one constructor parameter of type [T] receives the wrapped service;
     * where there are several, say which with `argument(name, Argument.Inner)`.
     */
    public inline fun <reified T : Any> decorates(
        priority: Int = 0,
        qualifier: String? = null,
    ): DefinitionConfigurator = decorates(ServiceKey(typeRef<T>(), qualifier), priority)

    /** Sets the argument for the parameter [name]. For when the type does not single one out. */
    public fun argument(
        name: String,
        argument: Argument,
    ): DefinitionConfigurator = change { it.copy(arguments = it.arguments + (name to argument)) }

    public fun argument(
        type: TypeRef,
        argument: Argument,
    ): DefinitionConfigurator = change { it.copy(typedArguments = it.typedArguments + TypedArgument(type, argument)) }

    /**
     * Sets the argument for the one parameter that takes a [P], without naming it:
     * `argument<List<Middleware>>(tagged<MiddlewareTag>())`. Two parameters of that type have to be named.
     */
    public inline fun <reified P> argument(argument: Argument): DefinitionConfigurator = argument(typeRef<P>().nonNull(), argument)

    /** Passes the service [service] asks for to the one parameter of that type. */
    public fun argument(service: Argument.Service): DefinitionConfigurator =
        when (val reference = service.reference) {
            is ServiceReference.ByKey -> argument(reference.key.type, service)
            is ServiceReference.ById -> throw IllegalArgumentException("A reference by id has no type to match; name the parameter")
        }

    public fun argument(value: kotlin.time.Duration): DefinitionConfigurator = argument(typeRef<kotlin.time.Duration>(), literal(value))

    /** Passes [value] to the one parameter of its type. The same for the other kinds of constant below. */
    public fun argument(value: String): DefinitionConfigurator = argument(typeRef<String>(), literal(value))

    public fun argument(value: Boolean): DefinitionConfigurator = argument(typeRef<Boolean>(), literal(value))

    public fun argument(value: Number): DefinitionConfigurator =
        argument(TypeRef(requireNotNull(value::class.qualifiedName)), literal(value))

    public fun argument(value: Enum<*>): DefinitionConfigurator =
        argument(TypeRef(requireNotNull(value.declaringJavaClass.kotlin.qualifiedName)), literal(value))

    /** A new instance per consumer. */
    public fun prototype(): DefinitionConfigurator = change { it.copy(scope = Scope.Prototype) }

    /** Defers instantiation until the service is first accessed. */
    public fun lazy(enabled: Boolean = true): DefinitionConfigurator = change { it.copy(lazy = enabled) }

    /** Instantiates a shared service immediately when the container is built (the default). */
    public fun eager(): DefinitionConfigurator = lazy(false)

    public fun autowired(enabled: Boolean): DefinitionConfigurator = change { it.copy(autowired = enabled) }

    public fun autoconfigured(enabled: Boolean): DefinitionConfigurator = change { it.copy(autoconfigured = enabled) }

    private fun change(change: (ServiceDefinition) -> ServiceDefinition): DefinitionConfigurator = apply { container.update(id, change) }
}

/** Which types `discover` registers and which classes it leaves out. */
@ContainerDsl
public class DiscoveryBuilder internal constructor() {
    private val classes = mutableSetOf<String>()

    internal val targets: List<TypeRef>
        field = mutableListOf<TypeRef>()

    /** Restricts discovery to subtypes of [type]. */
    public fun services(type: TypeRef) {
        targets += type
    }

    /**
     * Restricts discovery to subtypes of [T]. When called at least once, only matching subtypes are
     * registered and any wiring failure fails compilation immediately.
     */
    public inline fun <reified T : Any> services() {
        services(typeRef<T>())
    }

    /** Leaves out the class of this [qualifiedName]. */
    public fun exclude(qualifiedName: String) {
        classes += qualifiedName
    }

    /** Leaves out [T]. */
    public inline fun <reified T : Any> exclude() {
        exclude(typeRef<T>().qualifiedName)
    }

    internal fun excludes(type: TypeRef): Boolean = type.qualifiedName in classes
}

public class AutoconfigureBuilder internal constructor() {
    private val collected = mutableListOf<Tag>()

    internal val tags: List<Tag> get() = collected

    public fun tag(tag: Tag) {
        collected += tag
    }
}

public data class TaggedService<T : Tag>(
    val definition: ServiceDefinition,
    val tag: T,
)

public data class EntryPoint(
    val name: String,
    val key: ServiceKey,
)

internal data class RegisteredPass(
    val pass: BuilderPass,
    val phase: PassPhase,
    val priority: Int,
)

internal data class Autoconfiguration(
    val type: TypeRef,
    val tags: List<Tag>,
)

/**
 * Which function a reference points at. Read off the reference the compiler generated, which carries its
 * owner and name without `kotlin-reflect`; this runs in the processor, so the JVM is a given.
 */
@PublishedApi
internal fun KFunction<*>.toInstantiation(): Instantiation {
    val reference = this as? CallableReference ?: throw IllegalArgumentException("$this is not a function reference like ::create")

    return when (val owner = reference.owner) {
        is KClass<*> -> {
            val type = TypeRef(requireNotNull(owner.qualifiedName))
            // A Kotlin object is reached through its INSTANCE; a class without one can only offer statics.
            val isObject = owner.java.declaredFields.any { it.name == "INSTANCE" }

            if (isObject) Instantiation.ObjectFunction(type, reference.name) else Instantiation.StaticFactory(type, reference.name)
        }
        is ClassBasedDeclarationContainer -> Instantiation.Function(owner.jClass.packageName, reference.name)
        else -> throw IllegalArgumentException("Cannot tell where ${reference.name} is declared")
    }
}
