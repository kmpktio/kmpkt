package io.kmpkt.snapshot.model

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.model.BaseAggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.snapshot.event.SnapshotEvent

/**
 * Convenience base for event-sourced aggregates that also support [Snapshotable] checkpointing.
 *
 * Rehydrating via [replayAll] restores from any [SnapshotEvent] and replays subsequent events
 * sequentially.
 */
public abstract class SnapshotableAggregateRoot<A : SnapshotableAggregateRoot<A>>(
    id: AggregateId<A>,
) : BaseAggregateRoot<A>(id),
    Snapshotable<A> {
    protected fun replayAll(history: List<DomainEvent<A>>) {
        history.forEach { event ->
            if (event is SnapshotEvent<*>) restore(event) else replay(event)
        }
    }
}
