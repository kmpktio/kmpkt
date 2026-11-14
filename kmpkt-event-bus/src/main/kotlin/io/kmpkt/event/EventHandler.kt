package io.kmpkt.event

import io.kmpkt.messenger.message.MessageHandler

/**
 * Reacts to one kind of [Event]. Contravariant in [E], so a handler written against an event's supertype also
 * serves its subtypes.
 *
 * Unconstrained on purpose: a handler may do whatever it likes. Under event sourcing prefer a
 * `kmpkt-policy` `Policy` (event in, commands out) or a `kmpkt-projection` `Projector` (event in,
 * projection changes out) - both are constrained versions of this, and the constraint is what makes a side
 * effect individually retryable and testable without an event store. An audit log or an outgoing webhook is
 * still a fair use of a plain handler.
 */
public interface EventHandler<in E : Event> : MessageHandler<E, Unit> {
    public suspend fun handle(event: E)

    /** What the bus calls. An event handler is a message handler for its event. */
    override suspend fun invoke(message: E): Unit = handle(message)
}
