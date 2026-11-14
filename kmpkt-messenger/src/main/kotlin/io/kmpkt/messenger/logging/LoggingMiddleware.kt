package io.kmpkt.messenger.logging

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import org.slf4j.Logger

/** Logs when a message enters and leaves the rest of the chain, and the failure if it threw. */
public class LoggingMiddleware(
    private val logger: Logger,
) : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        val messageName = envelope.message::class.simpleName
        logger.debug("Dispatching message: {}", messageName)

        return try {
            val result = next(envelope)
            logger.debug("Message handled successfully: {}", messageName)
            result
        } catch (e: Exception) {
            logger.error("Error handling message: {}", messageName, e)
            throw e
        }
    }
}
