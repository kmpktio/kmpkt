package io.kmpkt.projection.fixture

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import java.time.Instant

/** Minimal aggregate used only to give test events a type - no behavior of its own is needed here. */
internal class TestAggregate(
    override val id: AggregateId<TestAggregate>,
) : AggregateRoot<TestAggregate> {
    override val version: Long = 0
    override val persistedVersion: Long = 0

    override fun pullRecordedEvents(): List<DomainEvent<TestAggregate>> = emptyList()
}

internal data class ItemAdded(
    override val aggregate: AggregateId<TestAggregate>,
    override val version: Long,
    override val occurredAt: Instant,
    val name: String,
) : DomainEvent<TestAggregate>

internal data class ItemRemoved(
    override val aggregate: AggregateId<TestAggregate>,
    override val version: Long,
    override val occurredAt: Instant,
    val name: String,
) : DomainEvent<TestAggregate>
