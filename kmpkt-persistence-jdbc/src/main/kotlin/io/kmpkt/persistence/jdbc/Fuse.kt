package io.kmpkt.persistence.jdbc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Local one-shot guard; extract into a dedicated kmpkt-resilience module once more
// resilience primitives (e.g. TokenBucket, CircuitBreaker, Backoff) are needed.
internal class Fuse {
    private val mutex = Mutex()
    private var blown = false

    suspend fun runOnce(block: suspend () -> Unit) {
        mutex.withLock {
            if (blown) return
            block()
            blown = true
        }
    }
}
