package io.kmpkt.messenger.retry

import io.kmpkt.messenger.message.Envelope
import kotlin.time.Duration

/**
 * Decides whether a message that failed is tried again, and after how long.
 *
 * Applied where a message is taken off a transport: by [io.kmpkt.messenger.transport.SyncTransport] at once
 * and in place, by [io.kmpkt.messenger.worker.TransportWork] by sending the message back to its transport.
 * The bus itself knows nothing of retries.
 *
 * Not to be confused with `io.kmpkt.worker.RetryStrategy`, which is about a whole pass of work failing -
 * the transport being unreachable - rather than one message.
 */
public interface RetryStrategy {
    /** Whether [envelope], which just failed with [failure], should be handled again. */
    public fun isRetryable(
        envelope: Envelope,
        failure: Throwable,
    ): Boolean

    /** How long to wait before that. Ignored where a retry is immediate. */
    public fun waitingTime(
        envelope: Envelope,
        failure: Throwable,
    ): Duration
}

/**
 * A failure that is worth trying again whatever the strategy thinks of failures in general: an optimistic
 * lock that lost, a connection that dropped.
 */
public interface RecoverableException

/**
 * A failure that will fail the same way every time, so that retrying it only delays the report.
 */
public interface UnrecoverableException
