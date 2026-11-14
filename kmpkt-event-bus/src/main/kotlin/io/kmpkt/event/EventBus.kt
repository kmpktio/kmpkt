package io.kmpkt.event

import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.handler.HandlerPolicy

/**
 * Publishes an [Event] to every handler registered for it, by putting it on a [MessageBus].
 *
 * Thin on purpose, the same way `kmpkt-command`'s `CommandBus` is: it exists so an application states what
 * it is doing ("publishing an event") rather than how, and everything else - middleware, routing, retries -
 * stays the messenger's job.
 *
 * **This is the CQRS-without-event-sourcing path.** A command handler publishes here and handlers react. Under
 * event sourcing nothing publishes: the store is the append-only log consumers poll, so a policy or a
 * projector reads events back out instead of being handed them. Both can be wired at once, and an audit log
 * under event sourcing is a reasonable reason to.
 *
 * The bus it is given has to allow [HandlerPolicy.MULTIPLE]: an event with no handler is normal, and several
 * handlers for one event is the point. That is settled where the bus is built, not here.
 */
public class EventBus(
    private val bus: MessageBus,
) {
    public suspend fun publish(event: Event) {
        bus.dispatch(event)
    }
}
