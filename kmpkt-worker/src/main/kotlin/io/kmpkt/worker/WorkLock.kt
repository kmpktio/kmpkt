package io.kmpkt.worker

/**
 * Distributed or local mutual-exclusion lock used by [LockedWork] to ensure at most one worker
 * instance executes a [Work] pass at a time.
 */
public interface WorkLock {
    /**
     * Runs [block] while holding the lock named [name], or returns `null` without running [block]
     * if the lock is currently held elsewhere.
     */
    public suspend fun <R> underLock(
        name: String,
        block: suspend () -> R,
    ): R?
}
