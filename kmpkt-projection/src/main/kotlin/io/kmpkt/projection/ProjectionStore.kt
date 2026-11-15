package io.kmpkt.projection

/** The rows of a projection, as a [Projector] may read them: never the whole log, only its own projection. */
public interface ProjectionView<R : Any> {
    /** Every row [match] matches, in no particular order. An empty match returns all of them. */
    public suspend fun findAll(match: Match<R> = Match()): List<R>

    /** The one row [match] matches, or `null`. Fails if it matches more than one. */
    public suspend fun findOrNull(match: Match<R>): R?
}

/**
 * Where a projection's rows live. Read side for projectors, write side for whoever drives them, plus [clear]
 * for a rebuild.
 *
 * Deliberately not a general table abstraction: the operations are the two a [ProjectionChange] can be, and a
 * store is free to be a map, a SQL table or anything else - it only has to know how to derive a row's key.
 */
public interface ProjectionStore<R : Any> : ProjectionView<R> {
    /** Applies [changes] in order. Order matters: a `Remove` followed by a `Put` is not the reverse. */
    public suspend fun apply(changes: List<ProjectionChange<R>>)

    /** Discards every row. For a rebuild - a projection is derived, so throwing it away loses nothing. */
    public suspend fun clear()
}
