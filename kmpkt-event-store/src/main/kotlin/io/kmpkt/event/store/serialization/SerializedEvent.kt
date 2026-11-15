package io.kmpkt.event.store.serialization

/**
 * A [io.kmpkt.aggregate.event.DomainEvent] as a store holds it: [type] names which event it is,
 * [payload] carries its data. Keeping the two apart lets a store index and query by type without decoding.
 */
public data class SerializedEvent(
    public val type: String,
    public val payload: String,
)
