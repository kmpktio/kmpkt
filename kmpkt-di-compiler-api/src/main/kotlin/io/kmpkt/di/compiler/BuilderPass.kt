package io.kmpkt.di.compiler

/**
 * Changes the container while it is compiled - for example to collect tagged services.
 *
 * A pass reports what it cannot resolve through [ContainerBuilder.diagnostics] rather than throwing, and
 * the pipeline stops after the first pass that reported an error.
 */
public fun interface BuilderPass {
    public fun process(container: ContainerBuilder)
}

/**
 * When a pass runs. Five phases, in order; loading the extensions happens before all of them
 * and is not a phase.
 */
public sealed class PassPhase(
    internal val order: Int,
) {
    /** The default for a pass: definitions are complete, nothing is resolved yet. */
    public data object BeforeOptimization : PassPhase(0)

    /** Decorators, autowiring, tagged arguments, aliases, validation. */
    public data object Optimization : PassPhase(1)

    /** Every reference is resolved and checked; nothing has been removed. Empty by default. */
    public data object BeforeRemoving : PassPhase(2)

    /** Unreachable definitions go, single-use ones are inlined. */
    public data object Removing : PassPhase(3)

    /** What is left is what gets generated. */
    public data object AfterRemoving : PassPhase(4)
}
