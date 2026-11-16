package io.kmpkt.messenger.retry.middleware

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.stamp.SentToFailureTransportStamp

/**
 * Restores the original [ReceivedStamp] from [SentToFailureTransportStamp] when an envelope is
 * re-consumed from one of [failureTransportNames], so downstream middlewares (such as
 * [RetryMiddleware]) treat it as coming from its original transport.
 */
public class FailedMessageProcessingMiddleware(
    failureTransportNames: Collection<String>,
) : Middleware {
    private val failureTransportNames: Set<String> = failureTransportNames.toSet()

    public constructor(failureTransportName: String) : this(listOf(failureTransportName))

    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        val received = envelope.last<ReceivedStamp>() ?: return next(envelope)
        if (received.transportName !in failureTransportNames) return next(envelope)

        val original = envelope.last<SentToFailureTransportStamp>()?.originalTransportName ?: return next(envelope)
        return next(envelope.with(ReceivedStamp(original)))
    }
}
