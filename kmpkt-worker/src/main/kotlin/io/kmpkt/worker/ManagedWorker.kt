package io.kmpkt.worker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A worker that looks after itself: it owns its coroutine, survives its own failures, and stops when asked.
 * For running in the background of an application, next to others.
 *
 * Nothing of the coroutine machinery is visible from here - no scope to pass in, no job to hold on to. The
 * lifecycle is [start] and [stop], and that is deliberately all.
 *
 * A failure that [retry] has given up on ends **this worker only**: it is reported to [onGiveUp] and the
 * worker stops. One stuck subscription must not take the application, or every other worker, with it. Use
 * [UnmanagedWorker] where the opposite is wanted.
 */
public class ManagedWorker(
    override val name: String,
    work: Work,
    private val logger: Logger,
    pollInterval: Duration = 1.seconds,
    retry: RetryStrategy = RetryStrategy.backOff(),
    private val onGiveUp: suspend (Throwable) -> Unit = {},
) : Worker {
    private val loop = WorkLoop(name, work, logger, pollInterval, retry)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var stopping = false
    private var job: Job? = null

    /** Starts the loop. Calling it again while it runs does nothing. */
    public fun start() {
        if (job?.isActive == true) return

        logger.info("Worker '{}' starting", name)
        stopping = false
        job = scope.launch { run() }
    }

    /**
     * Runs the loop in the calling coroutine. A failure the retry strategy gave up on goes to [onGiveUp] and
     * this returns normally - it never comes out of here, which is what "managed" means. [start] is the usual
     * way in; this is for a caller that wants the loop in a coroutine of its own choosing but the same
     * containment.
     */
    override suspend fun run() {
        try {
            loop.run { stopping }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            onGiveUp(failure)
        }
    }

    /**
     * Asks the loop to finish its current pass and stop, waiting up to [timeout] for it. Past that it is
     * cancelled where it stands - the graceful part is the attempt, not a promise.
     *
     * Letting the pass finish matters because a pass is where a subscriber is halfway through something: an
     * event handled but its position not yet saved, an HTTP call in flight. Cancelling there is safe but
     * wasteful - the work is simply done again on the next start, which is why subscribers have to be
     * idempotent either way.
     */
    public suspend fun stop(timeout: Duration = 10.seconds) {
        val running = job ?: return
        stopping = true

        if (withTimeoutOrNull(timeout) { running.join() } == null) {
            logger.warn("Worker '{}' did not stop within {}, cancelling", name, timeout)
            running.cancelAndJoin()
        }
        job = null
    }

    /** Whether the loop is currently running. */
    public val isRunning: Boolean
        get() = job?.isActive == true
}

/** Creates a [ManagedWorker] for [work] using a logger named after [name]. */
public fun createManagedWorker(
    name: String,
    work: Work,
    pollInterval: Duration = 1.seconds,
): ManagedWorker =
    ManagedWorker(
        name = name,
        work = work,
        logger = LoggerFactory.getLogger("io.kmpkt.worker.$name"),
        pollInterval = pollInterval,
    )
