package io.kmpkt.resilience

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coroutine-safe one-shot guard that runs an action at most once after succeeding.
 *
 * If an attempt throws, the fuse remains intact so a subsequent call can retry.
 */
public class Fuse {
    private val mutex = Mutex()
    private var blown = false

    public suspend fun runOnce(block: suspend () -> Unit) {
        mutex.withLock {
            if (blown) return
            block()
            blown = true
        }
    }
}
