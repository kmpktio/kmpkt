package io.kmpkt.aggregate.value

import io.kmpkt.aggregate.model.AggregateRoot

/**
 * The name an aggregate's streams are filed under in the event log. Together with an [AggregateId] it
 * identifies one stream.
 *
 * [A] is a phantom type parameter - nothing at runtime depends on it - so that a store call can't be handed
 * the name of one aggregate and the id of another. Declare one per aggregate, next to the aggregate itself,
 * and hand it to whatever repository manages it:
 *
 * ```kotlin
 * companion object {
 *     val TYPE: AggregateType<ManagedList> = AggregateType.of<ManagedList>()
 * }
 * ```
 *
 * Still a value object with the name in it, rather than the class itself, for one reason: the name is
 * **persisted**. Every event in the log carries it, so it has to stay stable when the class does not - a
 * rename or a move of the aggregate must not orphan its streams, and then the fix is to keep the old name
 * explicitly: `AggregateType("ManagedList")`.
 */
@JvmInline
public value class AggregateType<A : AggregateRoot<A>>(
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        /**
         * The type of [A], named after the class - which is what it was written out by hand as everywhere
         * anyway, and spelling it twice only invites the two to drift.
         *
         * Uses `qualifiedName` rather than `simpleName` because the name has to be **unique**: two
         * `Temperature` aggregates in different packages would otherwise both be filed as `"Temperature"`,
         * and their streams would mix with nothing to notice it. The store cannot catch that - it sees only
         * `(type, id)`, and the type parameter is erased - so uniqueness has to come from the name itself.
         *
         * Two consequences, both deliberate. Moving an aggregate between packages changes its name and
         * orphans its existing streams: keep the old name explicitly (`AggregateType("old.pkg.Temperature")`)
         * when that happens. And `qualifiedName` is **JVM-only**, so this does not survive the move to
         * multiplatform - by then the name should come from an annotation on the aggregate, which a compiler
         * plugin can also check for collisions, which is the one way to catch them at all.
         */
        public inline fun <reified A : AggregateRoot<A>> of(): AggregateType<A> =
            AggregateType(
                A::class.qualifiedName
                    ?: error("An anonymous or local class cannot be an aggregate type; give it a name"),
            )
    }
}
