package io.kmpkt.worker

/**
 * Something that keeps running [Work] until told to stop. There are exactly two kinds, and they differ in one
 * thing only: **what becomes of a failure the retry strategy has given up on.**
 *
 * - [UnmanagedWorker] lets it out. The caller owns the coroutine and the consequences; for a process whose
 *   only job this is, dying with the exception is right.
 * - [ManagedWorker] keeps it in, reports it, and stops only itself. It owns its coroutine, and adds [start]
 *   and [stop] for running in the background of an application next to others.
 *
 * [run] is what they share, and its contract is deliberately the weaker of the two: it may throw. Hold the
 * concrete type where that matters.
 */
public sealed interface Worker {
    public val name: String

    /**
     * Runs the loop in the calling coroutine, returning when it is asked to stop or cancelled.
     *
     * Whether a failure the retry strategy gave up on comes out of here is the subtype's business - see
     * [UnmanagedWorker.run] and [ManagedWorker.run].
     */
    public suspend fun run()
}
