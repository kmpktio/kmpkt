package io.kmpkt.event.store

import io.kmpkt.aggregate.event.DomainEvent

/**
 * An [event] as read back from the log via [EventStore.loadFrom], together with its [position] - a stable,
 * append-order place among *every* event in the store, assigned once and never reused or shifted by later
 * deletes. Lets a consumer (a policy, a projector, ...) remember "I got up to position N" and resume from
 * there.
 *
 * Not parameterized by aggregate type: the log is global, so a batch read from it holds events of whatever
 * aggregates happen to have been written. A consumer that only cares about some of them matches on the event
 * type (`is ListEvent`).
 */
public data class StoredEvent(
    public val position: Long,
    public val event: DomainEvent<*>,
)
