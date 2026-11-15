package io.kmpkt.aggregate.model

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.value.AggregateId

/**
 * Event-sourced [AggregateRoot]: changes its state only by recording domain events; [updateState] is the only
 * place state actually changes, for both newly recorded and replayed events.
 *
 * @param A the concrete aggregate type itself, so [AggregateId] and [DomainEvent] are typed to this aggregate.
 */
public abstract class BaseAggregateRoot<A : BaseAggregateRoot<A>>(
    override val id: AggregateId<A>,
) : AggregateRoot<A> {
    override var version: Long = 0
        protected set

    private val recordedEvents = mutableListOf<DomainEvent<A>>()

    override val persistedVersion: Long get() = version - recordedEvents.size

    override fun pullRecordedEvents(): List<DomainEvent<A>> = recordedEvents.toList().also { recordedEvents.clear() }

    protected fun recordThat(event: DomainEvent<A>) {
        replay(event)
        recordedEvents += event
    }

    /** Applies a stored event without recording it again. Used to rebuild an aggregate from its history. */
    protected fun replay(event: DomainEvent<A>) {
        require(event.aggregate == id) {
            "Event aggregate id ${event.aggregate} does not match aggregate id $id"
        }
        require(event.version == version + 1) {
            "Event version ${event.version} is out of sync, expected ${version + 1}"
        }

        version = event.version
        updateState(event)
    }

    /**
     * Rehydrates the aggregate from a checkpoint event at [DomainEvent.version], bypassing the
     * sequential `version + 1` check used by [replay] for incremental events.
     */
    protected fun restore(event: DomainEvent<A>) {
        require(event.aggregate == id) {
            "Event aggregate id ${event.aggregate} does not match aggregate id $id"
        }
        require(version == 0L) {
            "Cannot restore from event at version ${event.version} when aggregate is already at version $version"
        }
        require(event.version > 0) {
            "Restored event version ${event.version} must be positive"
        }

        version = event.version
        updateState(event)
    }

    protected abstract fun updateState(event: DomainEvent<A>)
}
