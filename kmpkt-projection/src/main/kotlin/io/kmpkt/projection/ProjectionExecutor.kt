package io.kmpkt.projection

import io.kmpkt.aggregate.event.DomainEvent
import kotlin.reflect.KClass

/** Registry of [Projector]s for one projection, grouped by the event type each handles. */
public class ProjectionExecutor<R : Any>(
    initial: Map<KClass<out DomainEvent<*>>, List<Projector<*, R>>> = emptyMap(),
) {
    private val projectorsByEvent: MutableMap<KClass<out DomainEvent<*>>, MutableList<Projector<DomainEvent<*>, R>>> =
        initial
            .mapValues { (_, projectors) ->
                @Suppress("UNCHECKED_CAST")
                projectors.map { it as Projector<DomainEvent<*>, R> }.toMutableList()
            }.toMutableMap()

    /** Registers [projector] for [eventType]. Prefer the other [register] overload - it infers the type. */
    public fun <E : DomainEvent<*>> register(
        eventType: KClass<E>,
        projector: Projector<E, R>,
    ) {
        @Suppress("UNCHECKED_CAST")
        projectorsByEvent
            .getOrPut(eventType) { mutableListOf() }
            .add(projector as Projector<DomainEvent<*>, R>)
    }

    /** Registers [projector] for whichever event type its [Projector.project] actually takes. */
    public inline fun <reified E : DomainEvent<*>> register(projector: Projector<E, R>) {
        register(E::class, projector)
    }

    /**
     * Whether any projector here is registered for [event]'s type.
     *
     * A `ProjectionTransport` asks before emitting an event, so a projection reading a log full of event types
     * it does not care about emits nothing for them instead of a message its handler would discard.
     */
    public fun handles(event: DomainEvent<*>): Boolean = event::class in projectorsByEvent

    /**
     * What every projector registered for [event]'s type decided, in registration order.
     *
     * Each one reads through [view] before the next one's changes are applied, so a projector must not rely on
     * seeing what an earlier projector in the same event decided. Where that matters, it is one projector.
     */
    public suspend fun changesFor(
        event: DomainEvent<*>,
        view: ProjectionView<R>,
    ): List<ProjectionChange<R>> =
        projectorsByEvent[event::class]
            .orEmpty()
            .flatMap { projector -> projector.project(event, view) }
}
