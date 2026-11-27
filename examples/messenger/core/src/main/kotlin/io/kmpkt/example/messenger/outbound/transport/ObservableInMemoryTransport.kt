package io.kmpkt.example.messenger.outbound.transport

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.stamp.ErrorDetailsStamp
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.messenger.transport.Transport
import org.slf4j.Logger

/**
 * Wraps [InMemoryTransport] so dead-lettered failures are logged via SLF4J [Logger] as soon as
 * a message is routed to the failure transport, avoiding unsafe casts in consumers.
 */
class ObservableInMemoryTransport(
    private val logger: Logger,
    private val delegate: InMemoryTransport = InMemoryTransport(),
) : Transport by delegate {
    override suspend fun send(envelope: Envelope): Envelope {
        envelope.last<ErrorDetailsStamp>()?.exceptionClass?.let { failure ->
            logger.error("dead-letter: $failure")
        }
        return delegate.send(envelope)
    }
}
