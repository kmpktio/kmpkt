package io.kmpkt.worker

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.slf4j.Logger
import kotlin.time.Duration

/**
 * The loop both worker kinds run: a pass that handled something is followed immediately by the next, a pass
 * that handled nothing by a wait of [pollInterval]. A pass that throws goes to [retry]; when that gives up,
 * the failure is rethrown and the loop ends.
 *
 * Internal on purpose - what differs between the two kinds is who owns the coroutine and what becomes of that
 * failure, not the loop itself.
 */
internal class WorkLoop(
    private val name: String,
    private val work: Work,
    private val logger: Logger,
    private val pollInterval: Duration,
    private val retry: RetryStrategy,
) {
    /** Runs until [stopRequested] answers true, or until [retry] gives up and the failure is rethrown. */
    suspend fun run(stopRequested: () -> Boolean) {
        var attempt = 0

        while (!stopRequested()) {
            // A pass that always finds work would otherwise never reach a suspension point to be cancelled at.
            currentCoroutineContext().ensureActive()

            val handled =
                try {
                    work.run().also { attempt = 0 }
                } catch (failure: Exception) {
                    attempt += 1
                    val wait = retry.delayBefore(attempt)
                    if (wait == null) {
                        logger.error("Worker '{}' giving up after {} attempt(s)", name, attempt, failure)
                        throw failure
                    }
                    logger.warn("Worker '{}' failed, retrying in {} (attempt {})", name, wait, attempt, failure)
                    delay(wait)
                    continue
                }

            if (handled == 0) delay(pollInterval)
        }

        logger.info("Worker '{}' stopped", name)
    }
}
