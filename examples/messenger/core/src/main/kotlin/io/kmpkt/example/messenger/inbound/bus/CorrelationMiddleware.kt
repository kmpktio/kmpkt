package io.kmpkt.example.messenger.inbound.bus

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Stamp
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Attaches a unique correlation ID to each command envelope that enters the bus without one,
 * allowing middleware pipelines, async retries, and logs to be traced across bus executions.
 */
class CorrelationMiddleware(
    private val logger: Logger,
) : Middleware {
    private val sequence = AtomicInteger(0)

    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        // Assign a fresh ID when a command first enters (or is re-dispatched on retry without a stamp).
        val tagged =
            if (envelope.last<CorrelationStamp>() == null) {
                val id = "corr-${sequence.incrementAndGet()}"
                logger.info("correlation: $id")
                envelope.with(CorrelationStamp(id))
            } else {
                envelope
            }
        return next(tagged)
    }

    data class CorrelationStamp(
        val correlationId: String,
    ) : Stamp
}
