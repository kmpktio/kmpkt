package io.kmpkt.policy

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.command.Command
import kotlin.reflect.KClass

/** Registry of [Policy]s, grouped by the event type each handles, collecting the [Command]s they decide on. */
public class PolicyExecutor(
    initial: Map<KClass<out DomainEvent<*>>, List<Policy<*>>> = emptyMap(),
) {
    private val policiesByEvent: MutableMap<KClass<out DomainEvent<*>>, MutableList<Policy<DomainEvent<*>>>> =
        initial
            .mapValues { (_, policies) ->
                @Suppress("UNCHECKED_CAST")
                policies.map { it as Policy<DomainEvent<*>> }.toMutableList()
            }.toMutableMap()

    /** Registers [policy] for [eventType]. Prefer the other [register] overload - it infers [eventType] for you. */
    public fun <E : DomainEvent<*>> register(
        eventType: KClass<E>,
        policy: Policy<E>,
    ) {
        @Suppress("UNCHECKED_CAST")
        policiesByEvent
            .getOrPut(eventType) { mutableListOf() }
            .add(policy as Policy<DomainEvent<*>>)
    }

    /** Registers [policy] for whichever event type its [Policy.decide] actually takes. */
    public inline fun <reified E : DomainEvent<*>> register(policy: Policy<E>) {
        register(E::class, policy)
    }

    public fun commandsFor(event: DomainEvent<*>): List<Command> {
        val matching = policiesByEvent[event::class].orEmpty()
        return matching.flatMap { policy -> policy.decide(event) }
    }
}
