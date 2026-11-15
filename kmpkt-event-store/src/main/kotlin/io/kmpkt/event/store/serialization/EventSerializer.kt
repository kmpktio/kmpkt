package io.kmpkt.event.store.serialization

import io.kmpkt.aggregate.event.DomainEvent

/**
 * Turns a [DomainEvent] into something a store can persist, and back. Deliberately says nothing about *how* -
 * a store depends on this port, never on a serialization library, so an application can bring its own.
 *
 * An implementation has to recover the concrete event type from [SerializedEvent.type], which rules out
 * nothing more than that: [DomainEvent] can't be `sealed` across module boundaries, so the type-to-class
 * mapping is the implementation's problem. The kotlinx adapter solves it by taking the application's own
 * sealed event hierarchy; a reflective one would look the class up by name.
 */
public interface EventSerializer {
    public fun serialize(event: DomainEvent<*>): SerializedEvent

    public fun deserialize(serialized: SerializedEvent): DomainEvent<*>
}
