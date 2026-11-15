package io.kmpkt.snapshot.model

import io.kmpkt.aggregate.model.AggregateRoot

/**
 * An [AggregateRoot] that can record a [io.kmpkt.snapshot.event.SnapshotEvent] capturing its current state.
 */
public interface Snapshotable<A : AggregateRoot<A>> {
    public fun snapshot()
}
