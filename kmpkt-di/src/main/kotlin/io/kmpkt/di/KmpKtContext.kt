package io.kmpkt.di

/**
 * Marker interface for a scoped child container declared via `context<M, H, I> { ... }`
 * and extended across modules via `context<M> { ... }`.
 *
 * ```kotlin
 * object Store : KmpKtContext
 * ```
 */
public interface KmpKtContext
