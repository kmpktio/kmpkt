package io.kmpkt.di

/**
 * A scoped child container opened for a specific domain context [C].
 *
 * Implements [AutoCloseable] so short-lived scopes can be used with [ContextScope.invoke] and
 * long-lived or asynchronous scopes can be kept open and closed explicitly when finished.
 */
public interface ContextHandle<out C : Any> : AutoCloseable {
    public val context: C
}

/**
 * Projects this [ContextHandle] onto a module-specific view interface [V] registered via `view<V>()`.
 */
public inline fun <reified V : Any> ContextHandle<*>.view(): V =
    this as? V
        ?: error("Context handle ${this::class.qualifiedName} does not implement view ${V::class.qualifiedName}")

/**
 * Root-injected entry point to open or run scoped child containers from runtime input [I], returning [H].
 */
public interface ContextScope<in I : Any, out H : ContextHandle<*>> {
    /** Opens a new isolated child container for [input]. The caller is responsible for closing it. */
    public fun open(input: I): H

    /** Opens a child container for [input], runs [block] on it, and closes it afterwards. */
    public operator fun <R> invoke(
        input: I,
        block: H.() -> R,
    ): R = open(input).use(block)
}

/**
 * Convenience overload for a [ContextScope] that requires no runtime input.
 */
public operator fun <H : ContextHandle<*>, R> ContextScope<Unit, H>.invoke(
    block: H.() -> R,
): R = invoke(Unit, block)

/**
 * Factory for creating a domain context [C] from runtime [I] and optionally tearing it down on [destroy].
 *
 * Register implementations in the root container so they can inject root services.
 */
public interface ContextFactory<C : Any, in I : Any> {
    public fun create(input: I): C

    public fun destroy(context: C) {}
}
