package io.kmpkt.policy

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.command.Command

/**
 * "Given this event, then that command" - reacts to one event type, deciding which [Command]s (if any) should
 * run because of it. Pure: deciding is not the same as dispatching, so a [Policy] never dispatches anything
 * itself - that's for whoever consumes [PolicyExecutor]'s result to do. Which event type [E] this handles is
 * inferred from here when registering it with a [PolicyExecutor] - nothing else to declare.
 *
 * Contravariant in [E]: a policy written against an aggregate's whole event hierarchy can be registered for
 * each of the concrete types that should actually trigger it.
 */
public interface Policy<in E : DomainEvent<*>> {
    public fun decide(event: E): List<Command>
}
