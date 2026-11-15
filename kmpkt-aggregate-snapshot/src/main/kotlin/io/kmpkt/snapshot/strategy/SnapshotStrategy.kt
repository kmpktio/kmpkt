package io.kmpkt.snapshot.strategy

import io.kmpkt.aggregate.model.AggregateRoot

/** Decides whether [SnapshotAggregateRepository][io.kmpkt.snapshot.repository.SnapshotAggregateRepository] should take a snapshot after a save. */
public fun interface SnapshotStrategy {
    /** Called once per save with the stream's version before and after the just-appended events. */
    public fun shouldSnapshot(
        previousVersion: Long,
        newVersion: Long,
    ): Boolean

    /** Returns whether a snapshot should be taken for [aggregate] based on its persisted and current versions. */
    public fun shouldSnapshot(aggregate: AggregateRoot<*>): Boolean =
        shouldSnapshot(aggregate.persistedVersion, aggregate.version)
}
