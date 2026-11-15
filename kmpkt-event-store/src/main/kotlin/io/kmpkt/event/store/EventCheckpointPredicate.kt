package io.kmpkt.event.store

import io.kmpkt.aggregate.event.DomainEvent

/**
 * Identifies events that act as a stream checkpoint (for example a snapshot).
 *
 * - [EventStore.load] may start reading a stream from its latest checkpoint instead of version 1.
 * - [EventStore.loadFrom] and [EventStore.latestPosition] skip checkpoint events so projections
 *   and subscribers only see incremental domain events.
 */
public fun interface EventCheckpointPredicate {
    public fun isCheckpoint(event: DomainEvent<*>): Boolean

    public companion object {
        public val NONE: EventCheckpointPredicate = EventCheckpointPredicate { false }
    }
}

/** Default [EventCheckpointPredicate] that treats every event as a normal domain event. */
public class NoOpEventCheckpointPredicate : EventCheckpointPredicate {
    override fun isCheckpoint(event: DomainEvent<*>): Boolean = false
}
