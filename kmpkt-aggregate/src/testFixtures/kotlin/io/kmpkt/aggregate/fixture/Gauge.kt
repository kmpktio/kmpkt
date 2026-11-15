package io.kmpkt.aggregate.fixture

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.model.BaseAggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateType
import java.time.Clock
import java.time.Instant

/**
 * A second aggregate type, so tests can show what one global log means: that two aggregates share the log and
 * its ordering, and that a stream is identified by type *and* id rather than id alone.
 */
class Gauge private constructor(
    id: AggregateId<Gauge>,
) : BaseAggregateRoot<Gauge>(id) {
    fun read(clock: Clock) {
        recordThat(GaugeRead(aggregate = id, version = version + 1, occurredAt = clock.instant()))
    }

    override fun updateState(event: DomainEvent<Gauge>): Unit = Unit

    companion object {
        val TYPE: AggregateType<Gauge> = AggregateType.of<Gauge>()

        fun new(id: AggregateId<Gauge>): Gauge = Gauge(id)
    }
}

data class GaugeRead(
    override val aggregate: AggregateId<Gauge>,
    override val version: Long,
    override val occurredAt: Instant,
) : DomainEvent<Gauge>

/** An id backed by a plain string, so two aggregate types can deliberately be given the *same* id. */
data class SharedId<A : io.kmpkt.aggregate.model.AggregateRoot<A>>(
    val value: String,
) : AggregateId<A> {
    override fun toString(): String = value
}
