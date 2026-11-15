package io.kmpkt.projection

/**
 * What a [Projector] decided should happen to a projection because of one event. Plain data, so a projector
 * can be tested by comparing what it returns - no store, no event log, nothing to fake beyond the view it read
 * from.
 *
 * Two cases, which is all a row-shaped read model needs: a row is written, or rows go away. A partial update
 * is [Put] of a row the projector read from its [ProjectionView] and copied - which is also what gives it the
 * values the event itself doesn't carry.
 */
public sealed interface ProjectionChange<R : Any>

/** Writes [row], replacing whatever was under its key. */
public data class Put<R : Any>(
    public val row: R,
) : ProjectionChange<R>

/** Removes every row [match] matches - a whole list's rows, say, or exactly one. */
public data class Remove<R : Any>(
    public val match: Match<R>,
) : ProjectionChange<R>
