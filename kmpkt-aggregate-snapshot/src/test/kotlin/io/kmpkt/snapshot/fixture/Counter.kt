package io.kmpkt.snapshot.fixture

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.factory.AggregateFactory
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateType
import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.snapshot.event.SnapshotEvent
import io.kmpkt.snapshot.model.SnapshotableAggregateRoot
import java.time.Instant

/** Minimal aggregate used only to test the snapshotting machinery. */
class Counter private constructor(
    id: AggregateId<Counter>,
) : SnapshotableAggregateRoot<Counter>(id) {
    var value: Int = 0
        private set

    fun increment(occurredAt: Instant = Instant.now()) {
        recordThat(CounterIncremented(aggregate = id, version = version + 1, occurredAt = occurredAt))
    }

    override fun snapshot() {
        recordThat(CounterSnapshotted(aggregate = id, version = version + 1, occurredAt = Instant.now(), value = value))
    }

    override fun updateState(event: DomainEvent<Counter>) {
        value =
            when (event) {
                is CounterIncremented -> value + 1
                is CounterSnapshotted -> event.value
                else -> error("Unsupported event type: ${event::class}")
            }
    }

    companion object : AggregateFactory<Counter> {
        val TYPE: AggregateType<Counter> = AggregateType.of<Counter>()

        fun new(id: AggregateId<Counter> = AggregateUuid.generate()): Counter = Counter(id)

        override fun fromHistory(history: List<DomainEvent<Counter>>): Counter =
            Counter(history.first().aggregate).apply { replayAll(history) }
    }
}

data class CounterIncremented(
    override val aggregate: AggregateId<Counter>,
    override val version: Long,
    override val occurredAt: Instant,
) : DomainEvent<Counter>

data class CounterSnapshotted(
    override val aggregate: AggregateId<Counter>,
    override val version: Long,
    override val occurredAt: Instant,
    val value: Int,
) : SnapshotEvent<Counter>
