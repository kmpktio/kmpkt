package io.kmpkt.messenger.transport

/**
 * A [Transport] whose backing infrastructure (tables, queues, topics, consumer groups) can be
 * provisioned idempotently ahead of message consumption.
 */
public interface SetupableTransport : Transport {
    /** Ensures the transport's backing infrastructure exists and is ready to use. */
    public suspend fun setup()
}
