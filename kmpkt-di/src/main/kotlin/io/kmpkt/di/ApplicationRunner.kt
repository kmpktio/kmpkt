package io.kmpkt.di

/**
 * A service started by `KmpKt.run(args)` after the container is built and all non-lazy services have been
 * initialized.
 *
 * Implementations are autoconfigured and kept alive as entry points for `KmpKt.run`.
 */
public fun interface ApplicationRunner {
    public fun run(args: Array<String>)
}
