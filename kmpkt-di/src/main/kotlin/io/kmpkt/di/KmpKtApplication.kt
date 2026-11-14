package io.kmpkt.di

/**
 * Marker interface for an application entry point wired by a `Configuration<A>`.
 *
 * ```kotlin
 * object TodoApp : KmpKtApplication
 *
 * fun main(args: Array<String>) = KmpKt.run<TodoApp>(args)
 * ```
 */
public interface KmpKtApplication {
    public object Default : KmpKtApplication
}
