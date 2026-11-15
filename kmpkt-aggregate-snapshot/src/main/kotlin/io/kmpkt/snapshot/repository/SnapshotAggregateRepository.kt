package io.kmpkt.snapshot.repository

import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.repository.AggregateRepository
import io.kmpkt.snapshot.model.Snapshotable
import io.kmpkt.snapshot.strategy.SnapshotStrategy

/**
 * [AggregateRepository] decorator that records a snapshot event on [Snapshotable] aggregates before
 * delegating to [inner], so domain events and snapshot are appended atomically in one transaction.
 */
public class SnapshotAggregateRepository<A : AggregateRoot<A>>(
    private val inner: AggregateRepository<A>,
    private val snapshotStrategy: SnapshotStrategy,
) : AggregateRepository<A> by inner {
    override suspend fun save(aggregate: A) {
        takeSnapshotIfNeeded(aggregate)
        inner.save(aggregate)
    }

    private fun takeSnapshotIfNeeded(aggregate: A) {
        if (aggregate !is Snapshotable<*>) return
        if (!snapshotStrategy.shouldSnapshot(aggregate)) return

        aggregate.snapshot()
    }
}
