package io.kmpkt.aggregate.fixture

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.factory.AggregateFactory
import io.kmpkt.aggregate.model.BaseAggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateType
import io.kmpkt.aggregate.value.AggregateUuid
import java.time.Clock
import java.time.Instant

/** Minimal aggregate used only to test the generic event-sourcing machinery. */
class Counter private constructor(
    id: AggregateId<Counter>,
) : BaseAggregateRoot<Counter>(id) {
    var value: Int = 0
        private set

    fun increment(clock: Clock = Clock.systemUTC()) {
        recordThat(CounterIncremented(aggregate = id, version = version + 1, occurredAt = clock.instant()))
    }

    override fun updateState(event: DomainEvent<Counter>) {
        value =
            when (event) {
                is CounterIncremented -> value + 1
                else -> error("Unsupported event type: ${event::class}")
            }
    }

    companion object : AggregateFactory<Counter> {
        val TYPE: AggregateType<Counter> = AggregateType.of<Counter>()

        fun new(id: AggregateId<Counter> = AggregateUuid.generate()): Counter = Counter(id)

        override fun fromHistory(history: List<DomainEvent<Counter>>): Counter =
            Counter(history.first().aggregate).apply { history.forEach(::replay) }
    }
}

data class CounterIncremented(
    override val aggregate: AggregateId<Counter>,
    override val version: Long,
    override val occurredAt: Instant,
) : DomainEvent<Counter>
