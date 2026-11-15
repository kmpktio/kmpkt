package io.kmpkt.event.store.repository

import io.kmpkt.aggregate.factory.AggregateFactory
import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.repository.AggregateRepository
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateType
import io.kmpkt.event.store.EventStore

/** [AggregateRepository] that always replays the full event history. No snapshotting. */
public class DefaultAggregateRepository<A : AggregateRoot<A>>(
    private val eventStore: EventStore,
    private val aggregateType: AggregateType<A>,
    private val factory: AggregateFactory<A>,
) : AggregateRepository<A> {
    override suspend fun save(aggregate: A) {
        val previousVersion = aggregate.persistedVersion
        val events = aggregate.pullRecordedEvents()
        if (events.isEmpty()) return

        eventStore.append(aggregateType, aggregate.id, previousVersion, events)
    }

    override suspend fun findOrNull(id: AggregateId<A>): A? {
        val history = eventStore.load(aggregateType, id)
        if (history.isEmpty()) return null

        val aggregate = factory.fromHistory(history)
        check(aggregate.id == id) { "Event store returned history for ${aggregate.id}, requested $id" }
        return aggregate
    }
}
