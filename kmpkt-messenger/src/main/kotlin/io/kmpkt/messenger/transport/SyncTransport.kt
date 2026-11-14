package io.kmpkt.messenger.transport

import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.handler.exception.HandlerFailedException
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.retry.RetryDecider
import io.kmpkt.messenger.retry.RetryStrategy
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.stamp.RedeliveryStamp
import io.kmpkt.messenger.stamp.SentToFailureTransportStamp
import io.kmpkt.messenger.transport.stamp.SentStamp

/**
 * Marks a message as received and hands it straight back to [messageBus] instead of queueing it.
 * Routing a message here is how "handle this inline" becomes a routing decision like any other,
 * which is the only way a [io.kmpkt.messenger.transport.stamp.RouteStamp] can override a message type
 * the routing table sends elsewhere back to inline handling.
 *
 * [messageBus] is `Lazy` because a bus is usually built *with* this transport already in its routing table.
 * [name] is whatever this transport was wired up as, used when nothing else said how the message got here.
 *
 * With a [retryStrategy], a message that fails is handled again at once and in place, for as long as the
 * strategy allows - the way a queued message would be redelivered, minus the wait, which nothing here could
 * honour without blocking the caller. Without one, the first failure is the caller's. A failure marked
 * [io.kmpkt.messenger.retry.UnrecoverableException] is never retried.
 *
 * With a [failureTransport], a message that is given up on - retries used up, or never retryable - is sent
 * there instead of the failure being thrown, and the caller gets the envelope back as it was put aside.
 * That is a real trade: whoever dispatched no longer learns that it failed.
 *
 * Nothing is ever queued here, so [receive]/[ack]/[reject] are mistakes rather than no-ops and say so.
 */
public class SyncTransport(
    private val messageBus: Lazy<MessageBus>,
    private val name: String,
    private val retryStrategy: RetryStrategy? = null,
    private val failureTransport: Transport? = null,
) : Transport {
    override suspend fun send(envelope: Envelope): Envelope {
        // The name it was routed under, so a ReceivedStamp reads the same as it would off a real transport.
        val alias = envelope.last<SentStamp>()?.transportName ?: name
        var attempt = envelope

        while (true) {
            try {
                return messageBus.value.dispatch(
                    attempt.with(ReceivedStamp(alias)),
                )
            } catch (failure: Exception) {
                // Even a failure that insists on a retry is bounded by the strategy: with no wait between
                // attempts, an unbounded retry would spin in place until the failure went away.
                val retry =
                    retryStrategy != null &&
                        RetryDecider.decideFrom(failure) != false &&
                        retryStrategy.isRetryable(attempt, failure)
                if (!retry) return putAside(attempt, alias, failure)

                attempt = attempt.progressOf(failure).with(RedeliveryStamp(RedeliveryStamp.retryCountOf(attempt) + 1))
            }
        }
    }

    private suspend fun putAside(
        envelope: Envelope,
        alias: String,
        failure: Exception,
    ): Envelope {
        // Already put aside once: failing again while it is being looked at is the looker's to see.
        if (failureTransport == null || envelope.last<SentToFailureTransportStamp>() != null) throw failure

        return failureTransport.send(
            envelope.with(SentToFailureTransportStamp(alias, failure::class.simpleName ?: "Unknown", failure.message)),
        )
    }

    /** What handling got done before [failure], so the handlers that succeeded are not run a second time. */
    private fun Envelope.progressOf(failure: Exception): Envelope {
        val handled = (failure as? HandlerFailedException)?.envelope ?: return this

        return handled.copy(stamps = handled.stamps.filterNot { it is ReceivedStamp }.toSet())
    }

    override suspend fun receive(): List<Envelope> =
        error("Cannot receive from the sync transport '$name': it hands messages straight back to the bus instead of queueing them")

    override suspend fun ack(envelope: Envelope): Unit =
        error("Cannot ack on the sync transport '$name': nothing is ever queued on it")

    override suspend fun reject(envelope: Envelope): Unit =
        error("Cannot reject on the sync transport '$name': nothing is ever queued on it")
}
