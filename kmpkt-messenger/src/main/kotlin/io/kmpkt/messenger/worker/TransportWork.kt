package io.kmpkt.messenger.worker

import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.retry.RetryScheduledException
import io.kmpkt.messenger.stamp.ReceivedStamp
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
 * Retries are handled in the bus pipeline by [io.kmpkt.messenger.retry.middleware.RetryMiddleware], which
 * signals a scheduled redelivery via [RetryScheduledException]. With a [failureTransport], an envelope that
 * is permanently given up on is sent there before it is rejected, so that it is put aside rather than lost.
 * Both apply to queued transports; log-based transports (`kmpkt-projection`, `kmpkt-policy`) decide for
 * themselves what a rejection means and cannot be sent to.
 */
public class TransportWork(
    private val transport: Transport,
    private val transportName: String,
    private val messageBus: MessageBus,
    private val logger: Logger,
    private val failureTransport: Transport? = null,
) : Work {
    override suspend fun run(): Int {
        val envelopes = transport.receive()

        envelopes.forEach { envelope ->
            try {
                messageBus.dispatch(envelope.with(ReceivedStamp(transportName)))
                transport.ack(envelope)
            } catch (_: RetryScheduledException) {
                transport.reject(envelope)
            } catch (failure: Exception) {
                logger.error("Rejecting a message from transport '{}'", transportName, failure)
                putAside(envelope, failure)
                transport.reject(envelope)
            }
        }

        return envelopes.size
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
