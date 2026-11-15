package io.kmpkt.aggregate.value

import io.kmpkt.aggregate.model.AggregateRoot
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The default [AggregateId]: a random or name-derived UUID. Not every aggregate mints its own id: [generate] is
 * a convenience for the ones that do; [fromName] is for an aggregate whose identity comes from elsewhere (e.g.
 * an external system), deriving a stable id from it instead of wrapping it directly — so the id stays a real
 * UUID no matter what format the external id has (the original external id is then not recoverable from this;
 * carry it separately if you need it back).
 */
@OptIn(ExperimentalUuidApi::class)
@JvmInline
public value class AggregateUuid<A : AggregateRoot<A>>(
    public val value: Uuid,
) : AggregateId<A> {
    override fun toString(): String = value.toString()

    public companion object {
        public fun <A : AggregateRoot<A>> generate(): AggregateUuid<A> = AggregateUuid(Uuid.random())

        /** Deterministic id: the same (namespace, name) pair always maps to the same id. Pick your own namespace, see [v5]. */
        public fun <A : AggregateRoot<A>> fromName(
            namespace: Uuid,
            name: String,
        ): AggregateUuid<A> = AggregateUuid(Uuid.v5(namespace, name))
    }
}
