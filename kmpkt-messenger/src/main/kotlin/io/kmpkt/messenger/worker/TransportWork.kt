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
 * One pass of consuming [transports] in priority order: polls each transport from first to last,
 * processes the first non-empty batch by dispatching each envelope onto [messageBus], acking what
 * went through and rejecting what threw.
 *
 * Checking [transports] in order and returning as soon as one yields envelopes ensures higher-priority
 * transports are always drained before lower-priority ones.
 *
 * Retries are handled in the bus pipeline by [io.kmpkt.messenger.retry.middleware.RetryMiddleware],
 * which signals a scheduled redelivery via [RetryScheduledException]. When an envelope is permanently
 * given up on, it is forwarded to `failureTransports[transportName] ?: defaultFailureTransport` (if
 * configured) before being rejected on its source transport.
 */
public class TransportWork(
    private val transports: Map<String, Transport>,
    private val messageBus: MessageBus,
    private val logger: Logger,
    private val failureTransports: Map<String, Transport> = emptyMap(),
    private val defaultFailureTransport: Transport? = null,
) : Work {
    public constructor(
        transport: Transport,
        transportName: String,
        messageBus: MessageBus,
        logger: Logger,
        failureTransport: Transport? = null,
    ) : this(
        transports = mapOf(transportName to transport),
        messageBus = messageBus,
        logger = logger,
        defaultFailureTransport = failureTransport,
    )

    override suspend fun run(): Int {
        for ((transportName, transport) in transports) {
            val envelopes = transport.receive()
            if (envelopes.isEmpty()) continue

            envelopes.forEach { envelope ->
                try {
                    messageBus.dispatch(envelope.with(ReceivedStamp(transportName)))
                    transport.ack(envelope)
                } catch (_: RetryScheduledException) {
                    transport.reject(envelope)
                } catch (failure: Exception) {
                    logger.error("Rejecting a message from transport '{}'", transportName, failure)
                    putAside(transportName, envelope, failure)
                    transport.reject(envelope)
                }
            }

            return envelopes.size
        }

        return 0
    }

    private suspend fun putAside(
        transportName: String,
        envelope: Envelope,
        failure: Exception,
    ) {
        val failureTransport = failureTransports[transportName] ?: defaultFailureTransport
        // Not twice: a message that fails again while the failure transport itself is being consumed stays
        // rejected there, instead of being fed back into the queue it just came out of.
        if (failureTransport == null || envelope.last<SentToFailureTransportStamp>() != null) return

        failureTransport.send(
            envelope.with(SentToFailureTransportStamp(transportName, failure::class.simpleName ?: "Unknown", failure.message)),
        )
    }
}
