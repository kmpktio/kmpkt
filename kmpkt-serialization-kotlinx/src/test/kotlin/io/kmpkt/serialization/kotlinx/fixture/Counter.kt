package io.kmpkt.serialization.kotlinx.fixture

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.model.BaseAggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.serialization.kotlinx.AggregateUuidSerializer
import io.kmpkt.serialization.kotlinx.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Minimal aggregate used only to give the serializers something to round-trip. */
public class Counter internal constructor(
    id: AggregateId<Counter>,
) : BaseAggregateRoot<Counter>(id) {
    override fun updateState(event: DomainEvent<Counter>): Unit = Unit
}

@Serializable
public sealed interface CounterEvent : DomainEvent<Counter>

@Serializable
@SerialName("CounterIncremented")
public data class CounterIncremented(
    @Serializable(with = AggregateUuidSerializer::class) override val aggregate: AggregateId<Counter>,
    override val version: Long,
    @Serializable(with = InstantSerializer::class) override val occurredAt: Instant,
) : CounterEvent

@Serializable
@SerialName("CounterReset")
public data class CounterReset(
    @Serializable(with = AggregateUuidSerializer::class) override val aggregate: AggregateId<Counter>,
    override val version: Long,
    @Serializable(with = InstantSerializer::class) override val occurredAt: Instant,
) : CounterEvent

/** An event outside [CounterEvent], to show what a non-polymorphic serializer does. */
@Serializable
@SerialName("Unrelated")
public data class Unrelated(
    @Serializable(with = AggregateUuidSerializer::class) override val aggregate: AggregateId<Counter>,
    override val version: Long,
    @Serializable(with = InstantSerializer::class) override val occurredAt: Instant,
) : DomainEvent<Counter>
