package io.kmpkt.aggregate.event

import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.event.Event
import java.time.Instant

/**
 * A domain event of an aggregate of type [A]: an [Event] that knows which aggregate it belongs to, which
 * version of it it produced, and when. That is what an append-only log needs to store and replay it, and it
 * is the whole difference between event sourcing and plain CQRS - where an [Event] is published and forgotten,
 * a `DomainEvent` is appended and read back.
 *
 * Deliberately not `sealed`: concrete event types live in whatever module defines the aggregate, not here, so
 * this interface can't enumerate them. Consumers matching on a concrete, closed event hierarchy (e.g. a
 * sealed `ListEvent`) should match on that type instead of this one.
 */
public interface DomainEvent<A : AggregateRoot<A>> : Event {
    public val aggregate: AggregateId<A>
    public val version: Long
    public val occurredAt: Instant
}
