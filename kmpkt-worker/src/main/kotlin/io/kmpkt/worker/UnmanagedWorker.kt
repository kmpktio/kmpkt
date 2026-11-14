package io.kmpkt.worker

import org.slf4j.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A worker the caller looks after: it runs in the calling coroutine, and both a failure it has given up on and
 * a cancellation come straight back out.
 *
 * For a process whose only job this is - a CLI command, a container running one worker - where dying with the
 * exception and a non-zero exit code is exactly right, and where swallowing it would leave something that
 * looks alive and does nothing.
 *
 * ```kotlin
 * runBlocking { UnmanagedWorker("projections", work, logger).run() }
 * ```
 *
 * Stopping it is the caller's business too: cancel the coroutine it runs in. For a graceful stop between
 * passes rather than wherever it happens to be, use [ManagedWorker].
 */
public class UnmanagedWorker(
    override val name: String,
    work: Work,
    logger: Logger,
    pollInterval: Duration = 1.seconds,
    retry: RetryStrategy = RetryStrategy.backOff(),
) : Worker {
    private val loop = WorkLoop(name, work, logger, pollInterval, retry)

    /** Runs until cancelled, or until [RetryStrategy] gives up - and then **throws**, which is the point. */
    override suspend fun run() {
        loop.run { false }
    }
}
