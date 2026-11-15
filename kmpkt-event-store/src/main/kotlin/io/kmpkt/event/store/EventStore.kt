package io.kmpkt.event.store

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.exception.ConcurrencyConflict
import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateType

/**
 * One global append-only log of event streams. A stream is identified by an [AggregateType] and an
 * [AggregateId]; the aggregate type is data the log is keyed by, not something a store instance is built for,
 * so **one instance serves every aggregate**.
 *
 * That is not just tidier wiring: [loadFrom]'s positions are a single order over the whole log, which is what
 * lets a subscriber or projector that spans several aggregates see their events in the order they happened.
 * A store per aggregate type could not offer that order at all.
 */
public interface EventStore {
    /**
     * Appends events to one stream in one transaction.
     * @throws ConcurrencyConflict if the stream is not at [expectedVersion]. 0 means the stream must not exist yet.
     */
    public suspend fun <A : AggregateRoot<A>> append(
        type: AggregateType<A>,
        id: AggregateId<A>,
        expectedVersion: Long,
        events: List<DomainEvent<A>>,
    )

    /**
     * The stored history of one stream, oldest first, starting after [fromVersion] and at most [limit] events.
     *
     * [limit] is `null` by default, meaning the rest of the stream, because that is what rebuilding an
     * aggregate needs - a limit with a number for a default would silently rebuild from part of the history.
     * Pass one to read a slice, e.g. a page of a history view: `load(type, id, fromVersion = 40, limit = 20)`.
     */
    public suspend fun <A : AggregateRoot<A>> load(
        type: AggregateType<A>,
        id: AggregateId<A>,
        fromVersion: Long = 0,
        limit: Int? = null,
    ): List<DomainEvent<A>>

    /**
     * Every event in the log, oldest first, with a [StoredEvent.position] greater than [after] - across every
     * stream and every aggregate type. Up to [limit] of them. Position starts at 1; `after = 0` means from the
     * very beginning.
     */
    public suspend fun loadFrom(
        after: Long = 0,
        limit: Int = 100,
    ): List<StoredEvent>

    /**
     * The position of the newest event in the log, or `0` when it is empty. What a subscription that only
     * wants what happens from now on starts at.
     */
    public suspend fun latestPosition(): Long
}
