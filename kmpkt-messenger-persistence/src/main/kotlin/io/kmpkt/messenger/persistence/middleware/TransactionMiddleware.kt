package io.kmpkt.messenger.persistence.middleware

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.persistence.Persistence

/**
 * Runs every downstream middleware and handler inside [Persistence.inTransaction], rolling back all
 * persistence operations if handling fails.
 */
public class TransactionMiddleware(
    private val persistence: Persistence,
) : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: suspend (Envelope) -> Envelope,
    ): Envelope = persistence.inTransaction { next(envelope) }
}
