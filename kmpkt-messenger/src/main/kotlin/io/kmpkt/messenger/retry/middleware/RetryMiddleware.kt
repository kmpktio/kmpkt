package io.kmpkt.messenger.retry.middleware

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import io.kmpkt.messenger.retry.RetryDecider
import io.kmpkt.messenger.retry.RetryScheduledException
import io.kmpkt.messenger.retry.RetryStrategy
import io.kmpkt.messenger.stamp.DelayStamp
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.stamp.RedeliveryStamp
import io.kmpkt.messenger.transport.Transport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Retries messages that arrived from a queued [Transport] (marked with [ReceivedStamp]) by
 * re-sending them to their source transport with [RedeliveryStamp] and [DelayStamp] when
 * downstream handling fails and [RetryDecider.shouldRetry] approves another attempt.
 *
 * Throws [RetryScheduledException] after scheduling the redelivery so the worker rejects the
 * failed delivery without moving it to a failure transport.
 */
public class RetryMiddleware(
    private val strategies: Map<String, RetryStrategy>,
    private val transports: Map<String, Transport>,
    private val logger: Logger = LoggerFactory.getLogger(RetryMiddleware::class.java),
) : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        val transportName = envelope.last<ReceivedStamp>()?.transportName ?: return next(envelope)
        val strategy = strategies[transportName] ?: return next(envelope)
        val transport = transports[transportName] ?: return next(envelope)

        return try {
            next(envelope)
        } catch (failure: Exception) {
            if (!RetryDecider.shouldRetry(failure, envelope, strategy)) throw failure

            val retryCount = RedeliveryStamp.retryCountOf(envelope) + 1
            val delay = strategy.waitingTime(envelope, failure)
            logger.warn("Retrying a message from transport '{}' in {}, retry #{}", transportName, delay, retryCount, failure)
            transport.send(envelope.with(RedeliveryStamp(retryCount), DelayStamp(delay)))
            throw RetryScheduledException(failure)
        }
    }
}
