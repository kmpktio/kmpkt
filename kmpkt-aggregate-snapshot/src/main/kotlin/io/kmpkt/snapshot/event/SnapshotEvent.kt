package io.kmpkt.snapshot.event

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.model.AggregateRoot

/**
 * Marker for a [DomainEvent] that captures the full state of [A] at [version].
 *
 * When [SnapshotEventCheckpointPredicate] is registered, event stores can start loading an
 * aggregate's stream from its latest [SnapshotEvent] instead of replaying earlier events, and
 * event-log subscribers (`loadFrom`) skip snapshot events.
 */
public interface SnapshotEvent<A : AggregateRoot<A>> : DomainEvent<A>
