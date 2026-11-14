package io.kmpkt.messenger.worker

import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.retry.RetryDecider
import io.kmpkt.messenger.retry.RetryStrategy
import io.kmpkt.messenger.stamp.DelayStamp
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.stamp.RedeliveryStamp
import io.kmpkt.messenger.stamp.SentToFailureTransportStamp
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.worker.Work
import org.slf4j.Logger

/**
 * One pass of consuming [transport]: take whatever it has, dispatch each envelope onto [messageBus], ack what
 * went through and reject what threw.
 *
 * A single envelope's failure is handled here, because rejecting it and carrying on is what a transport means
 * by failure - the others in the batch are unaffected. A failure of [Transport.receive] itself is not handled
 * here: it propagates, and the [io.kmpkt.worker.Worker]'s retry strategy decides whether the transport
 * is worth asking again.
 *
 * With a [retryStrategy], an envelope that failed and may be retried is sent back to [transport] before it is
 * rejected, stamped with how often it has been redelivered and how long to wait.
 * With a [failureTransport], one that is given up on is sent there
 * before it is rejected, so that it is put aside rather than lost. Both are for a transport that is a queue. One that reads a log decides
 * for itself what a rejection means, and is given no strategy: `kmpkt-projection`'s leaves the event where
 * it is and comes back to it, `kmpkt-policy`'s records the failure and moves on, and neither can be sent to.
 */
public class TransportWork(
    private val transport: Transport,
    private val transportName: String,
    private val messageBus: MessageBus,
    private val logger: Logger,
    private val retryStrategy: RetryStrategy? = null,
    private val failureTransport: Transport? = null,
) : Work {
    override suspend fun run(): Int {
        val envelopes = transport.receive()

        envelopes.forEach { envelope ->
            try {
                messageBus.dispatch(envelope.with(ReceivedStamp(transportName)))
                transport.ack(envelope)
            } catch (failure: Exception) {
                redeliverOrGiveUp(envelope, failure)
                transport.reject(envelope)
            }
        }

        return envelopes.size
    }

    private suspend fun redeliverOrGiveUp(
        envelope: Envelope,
        failure: Exception,
    ) {
        val strategy = retryStrategy?.takeIf { RetryDecider.shouldRetry(failure, envelope, it) }

        if (strategy == null) {
            logger.error("Rejecting a message from transport '{}'", transportName, failure)
            putAside(envelope, failure)
            return
        }

        val retryCount = RedeliveryStamp.retryCountOf(envelope) + 1
        val delay = strategy.waitingTime(envelope, failure)
        logger.warn("Retrying a message from transport '{}' in {}, retry #{}", transportName, delay, retryCount, failure)
        transport.send(envelope.with(RedeliveryStamp(retryCount), DelayStamp(delay)))
    }

    private suspend fun putAside(
        envelope: Envelope,
        failure: Exception,
    ) {
        // Not twice: a message that fails again while the failure transport itself is being consumed stays
        // rejected there, instead of being fed back into the queue it just came out of.
        if (failureTransport == null || envelope.last<SentToFailureTransportStamp>() != null) return

        failureTransport.send(
            envelope.with(SentToFailureTransportStamp(transportName, failure::class.simpleName ?: "Unknown", failure.message)),
        )
    }
}
