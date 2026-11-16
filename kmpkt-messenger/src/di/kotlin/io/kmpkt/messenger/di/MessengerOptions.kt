package io.kmpkt.messenger.di

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.ContainerDsl
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.Tag
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.retry.RetryStrategy
import io.kmpkt.messenger.transport.Transport

/** Which transports and buses there are. Structure only: known when the container is compiled. */
@ContainerDsl
public class MessengerOptions {
    internal val transports: Map<TransportRef<*>, TransportDeclaration>
        field = LinkedHashMap<TransportRef<*>, TransportDeclaration>()

    internal val buses: Map<BusRef, BusOptions>
        field = LinkedHashMap<BusRef, BusOptions>()

    /**
     * The bus bound as plain `MessageBus`. Left out, a single bus is the default and several have none -
     * every consumer then has to say which one it means.
     */
    public var defaultBus: BusRef? = null

    /**
     * Where a message goes that a transport has given up on, unless that transport names its own. Left out,
     * such a message is rejected and gone - or, on a sync transport, its failure is the caller's.
     */
    public var failureTransport: TransportRef<*>? = null

    @PublishedApi
    internal fun <T : Transport> transport(
        name: String,
        type: TypeRef,
        configure: TransportOptions<T>.() -> Unit,
    ): TransportRef<T> {
        val ref = TransportRef<T>(name)
        val options = TransportOptions<T>().apply(configure)
        transports[ref] = TransportDeclaration(ref, type, options.retry, options.failureTransport)

        return ref
    }

    /**
     * Declares a transport: a [T] the container builds, known to routing through the reference this returns.
     *
     * ```kotlin
     * val failed = transport<InMemoryTransport>("failed")
     * val sync = transport<SyncTransport>("sync") {
     *     retry<MultiplierRetryStrategy> { maxRetries = 1 }
     *     failureTransport = failed
     * }
     * ```
     *
     * A `SyncTransport` hands a message straight back to the bus it came from, so each bus that uses one gets
     * an instance of its own. A transport named `sync` exists without being declared; declaring it is how it
     * gets a retry or a failure transport.
     */
    public inline fun <reified T : Transport> transport(
        name: String,
        noinline configure: TransportOptions<T>.() -> Unit = {},
    ): TransportRef<T> = transport(name, typeRef<T>(), configure)

    /** Declares the bus [name], or adds to it when it was declared before, returning its reference. */
    public fun bus(
        name: String,
        configure: BusOptions.() -> Unit = {},
    ): BusRef {
        val ref = BusRef(name)
        buses.getOrPut(ref) { BusOptions() }.apply(configure)

        return ref
    }
}

/**
 * A declared bus, as [MessengerOptions.defaultBus] refers to it. Only [MessengerOptions.bus] makes one,
 * so the default bus cannot name a bus that does not exist.
 */
@JvmInline
public value class BusRef internal constructor(
    public val name: String,
)

/**
 * A declared transport, as routing refers to it. Only [MessengerOptions.transport] makes one, so a route
 * cannot name a transport that does not exist.
 */
@JvmInline
public value class TransportRef<out T : Transport> internal constructor(
    public val name: String,
)

@ContainerDsl
public class TransportOptions<T : Transport> internal constructor() {
    internal var retry: RetryDeclaration? = null
        private set

    /** Where a message goes that this transport has given up on. Overrides [MessengerOptions.failureTransport]. */
    public var failureTransport: TransportRef<*>? = null

    @PublishedApi
    internal fun <S : RetryStrategy> retry(
        type: TypeRef,
        configure: StrategyOptions<S>.() -> Unit,
    ) {
        retry = RetryDeclaration(type, StrategyOptions<S>().apply(configure).arguments)
    }

    /**
     * Has a message that failed on this transport tried again, as [S] decides.
     * The block sets the strategy's own parameters:
     *
     * ```kotlin
     * retry<MultiplierRetryStrategy> {
     *     maxRetries = 5
     *     delay = 2.seconds
     * }
     * ```
     *
     * What can be set depends on [S]; for a strategy without settings the block is left out. On a sync
     * transport the retry is immediate; on a queue it is the worker's, by sending the message back.
     */
    public inline fun <reified S : RetryStrategy> retry(noinline configure: StrategyOptions<S>.() -> Unit = {}) {
        retry(typeRef<S>(), configure)
    }
}

/**
 * The constructor arguments of the retry strategy [S], set by name.
 *
 * What a block may set is given by extension properties on `StrategyOptions<S>` for that one [S] — see the
 * ones for `MultiplierRetryStrategy`. A strategy of the application's own gets its settings the same way:
 *
 * ```kotlin
 * var StrategyOptions<BackOffOnRateLimit>.limit: Int
 *     get() = get("limit")
 *     set(value) = set("limit", value, literal(value))
 * ```
 */
@ContainerDsl
public class StrategyOptions<S : RetryStrategy> internal constructor() {
    private val values = LinkedHashMap<String, Any>()

    internal val arguments: Map<String, Argument>
        field = LinkedHashMap<String, Argument>()

    /** Records [value] for the constructor parameter [parameter], to be generated as [argument]. */
    public fun set(
        parameter: String,
        value: Any,
        argument: Argument,
    ) {
        values[parameter] = value
        arguments[parameter] = argument
    }

    /** What was set for [parameter] in this block. Reading what was not set is a mistake: the default lives in the strategy. */
    @Suppress("UNCHECKED_CAST")
    public fun <V> get(parameter: String): V =
        values[parameter] as V? ?: throw IllegalStateException("\"$parameter\" was not set here; the strategy's own default applies")
}

@ContainerDsl
public class BusOptions internal constructor() {
    internal val middleware: List<ServiceKey>
        field = mutableListOf<ServiceKey>()

    internal val routes: Map<TypeRef, TransportRef<*>>
        field = LinkedHashMap<TypeRef, TransportRef<*>>()

    internal val handles: List<TypeRef>
        field = mutableListOf<TypeRef>()

    internal val defaultMiddleware: DefaultMiddlewareOptions = DefaultMiddlewareOptions()

    /** One handler per message for a command bus, any number for an event bus. */
    public var policy: HandlerPolicy = HandlerPolicy.SINGLE

    /**
     * Where a message goes that has no route of its own. Left out, that is the transport named `sync`: the
     * message is handed straight back to this bus and handled inline.
     */
    public var defaultTransport: TransportRef<*>? = null

    /** The same as setting [defaultTransport] (wildcard fallback route). */
    public fun routeAll(transport: TransportRef<*>) {
        defaultTransport = transport
    }

    @PublishedApi
    internal fun route(
        message: TypeRef,
        transport: TransportRef<*>,
    ) {
        routes[message] = transport
    }

    /** Sends [M] to [transport] instead of this bus's default. */
    public inline fun <reified M : Message> route(transport: TransportRef<*>) {
        route(typeRef<M>(), transport)
    }

    @PublishedApi
    internal fun handles(type: TypeRef) {
        handles += type
    }

    /**
     * Puts every handler that is an [H] on this bus - `handles<CommandHandler<*>>()` - and no other, apart
     * from one that names this bus in its `MessageHandlerTag`. A bus that says nothing here takes every
     * handler that does not name a bus, which with two buses is rarely what is meant.
     */
    public inline fun <reified H : MessageHandler<*, *>> handles() {
        handles(typeRef<H>())
    }

    @PublishedApi
    internal fun middleware(key: ServiceKey) {
        middleware += key
    }

    /**
     * Puts the service bound to [M] into this bus's chain, after the ones named before it. The service itself
     * is registered like any other - by the application or by another component.
     */
    public inline fun <reified M : Middleware> middleware(qualifier: String? = null) {
        middleware(ServiceKey(typeRef<M>(), qualifier))
    }

    /**
     * Controls which built-in middlewares (`send`, `retry`, `handle`) are wired into this bus.
     */
    public fun defaultMiddleware(configure: DefaultMiddlewareOptions.() -> Unit) {
        defaultMiddleware.apply(configure)
    }
}

@ContainerDsl
public class DefaultMiddlewareOptions internal constructor() {
    /** Includes `AddBusNameStampMiddleware` at the beginning of the chain. */
    public var busName: Boolean = true

    /** Includes `SendMessageMiddleware` when routes or non-sync transports exist. */
    public var send: Boolean = true

    /** Includes `FailedMessageProcessingMiddleware` when any transport uses a failure transport. */
    public var failedMessageProcessing: Boolean = true

    /** Includes `RetryMiddleware` when any queued transport defines a retry strategy. */
    public var retry: Boolean = true

    /** Includes `HandleMessageMiddleware` at the end of the chain. */
    public var handle: Boolean = true

    /** Disables all built-in middlewares (`busName`, `send`, `failedMessageProcessing`, `retry`, `handle`). */
    public fun none() {
        busName = false
        send = false
        failedMessageProcessing = false
        retry = false
        handle = false
    }
}

internal data class TransportDeclaration(
    val ref: TransportRef<*>,
    val type: TypeRef,
    val retry: RetryDeclaration? = null,
    val failureTransport: TransportRef<*>? = null,
) {
    val name: String get() = ref.name
}

internal class RetryDeclaration(
    val strategy: TypeRef,
    val arguments: Map<String, Argument>,
)

/**
 * Marks a message handler. Every registered [MessageHandler] gets one without asking; a handler tags itself
 * to name its [bus] or to be called ahead of others ([priority]).
 */
public data class MessageHandlerTag(
    val bus: String? = null,
    override val priority: Int = 0,
) : Tag

/**
 * Marks the handler registry of the bus [bus], which is where [RegisterMessageHandlersPass][io.kmpkt.messenger.di.pass.RegisterMessageHandlersPass] puts its handlers:
 * the ones that name the bus, and of the others those that are one of [handles] — or all, if that is empty.
 */
public data class HandlerRegistryTag(
    val bus: String,
    val handles: List<TypeRef> = emptyList(),
) : Tag
