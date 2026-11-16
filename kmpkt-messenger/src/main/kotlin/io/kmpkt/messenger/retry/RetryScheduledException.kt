package io.kmpkt.messenger.retry

/**
 * Thrown by [io.kmpkt.messenger.retry.middleware.RetryMiddleware] after it has scheduled a delayed
 * redelivery on the source transport, so [io.kmpkt.messenger.worker.TransportWork] rejects the
 * current delivery without forwarding it to a failure transport.
 */
public class RetryScheduledException(
    cause: Exception,
) : RuntimeException(cause)
