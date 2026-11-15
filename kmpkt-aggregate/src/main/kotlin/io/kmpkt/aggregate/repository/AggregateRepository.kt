package io.kmpkt.aggregate.repository

import io.kmpkt.aggregate.exception.AggregateNotFound
import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.value.AggregateId

/** Persists and retrieves aggregates of type [A], encapsulating the event store and snapshot store behind it. */
public interface AggregateRepository<A : AggregateRoot<A>> {
    public suspend fun save(aggregate: A)

    /**
     * The aggregate [id] identifies, or `null` if nothing is stored for it yet. The primitive of the two:
     * "not there yet" is an ordinary answer, not a failure, so nothing has to raise an exception for a caller
     * to catch straight back.
     */
    public suspend fun findOrNull(id: AggregateId<A>): A?

    /** The aggregate [id] identifies, for a caller that treats its absence as a bug. @throws AggregateNotFound */
    public suspend fun get(id: AggregateId<A>): A = findOrNull(id) ?: throw AggregateNotFound(id)
}
