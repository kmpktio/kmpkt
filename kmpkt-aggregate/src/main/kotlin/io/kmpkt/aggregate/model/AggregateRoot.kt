package io.kmpkt.aggregate.model

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.value.AggregateId

/**
 * What a repository/store needs from an aggregate root of type [A], regardless of how it tracks its own state
 * internally. [BaseAggregateRoot] is the event-sourced implementation (record/replay); define your own if you
 * need different bookkeeping while still fitting the rest of this library's ports.
 */
public interface AggregateRoot<A : AggregateRoot<A>> {
    public val id: AggregateId<A>

    /** Version after all applied events, including recorded but not yet persisted ones. */
    public val version: Long

    /** Version of the persisted stream the recorded events are appended to. */
    public val persistedVersion: Long

    /** Returns the recorded events and forgets them. Called by the repository on save. */
    public fun pullRecordedEvents(): List<DomainEvent<A>>
}
