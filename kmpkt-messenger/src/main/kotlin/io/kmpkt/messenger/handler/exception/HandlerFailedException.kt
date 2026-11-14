package io.kmpkt.messenger.handler.exception

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message

/**
 * One or more handlers of [message] threw. The first failure is the [cause]; all of them are in [failures],
 * which is where a retry looks for what kind of failure this was.
 *
 * [envelope] is the envelope as far as handling got: it carries the stamps of the handlers that succeeded,
 * so that a retry starting from it does not run those again.
 */
public class HandlerFailedException(
    message: Message,
    vararg causes: Throwable,
    public val envelope: Envelope? = null,
) : RuntimeException(
        "Failed to handle message: ${message::class.simpleName}",
        causes.firstOrNull(),
    ) {
    public val failures: List<Throwable> = causes.toList()
}
