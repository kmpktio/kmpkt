package io.kmpkt.projection

import kotlin.reflect.KProperty1

/**
 * Which rows of a projection something applies to, by example: every row whose [properties] hold these values.
 * An empty one matches all of them.
 *
 * Properties rather than column names, so a renamed field is a compile error instead of a silent no-op, and so
 * nothing has to warn about not passing user input as a key.
 */
@JvmInline
public value class Match<R : Any>(
    public val properties: Map<KProperty1<R, *>, Any?> = emptyMap(),
) {
    /** Whether [row] is one of the rows this matches. */
    public fun matches(row: R): Boolean = properties.all { (property, value) -> property.get(row) == value }
}

/** `match(AccessibleListRow::listId to listId.value)` */
public fun <R : Any> match(vararg properties: Pair<KProperty1<R, *>, Any?>): Match<R> = Match(properties.toMap())
