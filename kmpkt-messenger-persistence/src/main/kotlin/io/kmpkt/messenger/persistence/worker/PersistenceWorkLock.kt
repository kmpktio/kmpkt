package io.kmpkt.messenger.persistence.worker

import io.kmpkt.persistence.Persistence
import io.kmpkt.worker.WorkLock

/**
 * Adapts [Persistence.underLock] to [WorkLock] so any [io.kmpkt.worker.Work] can be wrapped in
 * [io.kmpkt.worker.LockedWork] without coupling `kmpkt-worker` to `kmpkt-persistence`.
 */
public class PersistenceWorkLock(
    private val persistence: Persistence,
) : WorkLock {
    override suspend fun <R> underLock(
        name: String,
        block: suspend () -> R,
    ): R? = persistence.underLock(name, block)
}
