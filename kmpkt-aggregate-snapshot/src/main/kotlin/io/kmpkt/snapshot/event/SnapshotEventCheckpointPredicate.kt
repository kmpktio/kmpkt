package io.kmpkt.snapshot.event

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.event.store.EventCheckpointPredicate

/** Marks [SnapshotEvent] instances as stream checkpoints for [io.kmpkt.event.store.EventStore]. */
public class SnapshotEventCheckpointPredicate : EventCheckpointPredicate {
    override fun isCheckpoint(event: DomainEvent<*>): Boolean = event is SnapshotEvent<*>
}
