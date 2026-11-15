package io.kmpkt.aggregate.factory

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.model.AggregateRoot

/**
 * Rebuilds an aggregate of type [A] from its full, non-empty event history. Typically implemented by the
 * aggregate's own companion object. Whether the result is actually the aggregate the caller asked for is the
 * repository's concern, not the factory's — it's the repository that knows what id it requested.
 */
public fun interface AggregateFactory<A : AggregateRoot<A>> {
    public fun fromHistory(history: List<DomainEvent<A>>): A
}
