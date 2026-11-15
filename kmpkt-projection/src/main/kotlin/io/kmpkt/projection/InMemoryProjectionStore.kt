package io.kmpkt.projection

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default, non-persistent [ProjectionStore]. Lost on restart - which costs nothing, because a projection is
 * rebuilt from the log anyway. Add a durable store when rebuilding every start gets too slow.
 *
 * [keyOf] is the projection's primary key: what makes two rows the same row. A [Put] replaces the row under
 * that key.
 */
public class InMemoryProjectionStore<R : Any>(
    private val keyOf: (R) -> Any,
) : ProjectionStore<R> {
    private val rows = mutableMapOf<Any, R>()
    private val mutex = Mutex()

    override suspend fun findAll(match: Match<R>): List<R> = mutex.withLock { rows.values.filter(match::matches) }

    override suspend fun findOrNull(match: Match<R>): R? =
        mutex.withLock {
            val matching = rows.values.filter(match::matches)
            require(matching.size <= 1) { "Expected at most one row for $match, found ${matching.size}" }
            matching.firstOrNull()
        }

    override suspend fun apply(changes: List<ProjectionChange<R>>) {
        mutex.withLock {
            changes.forEach { change ->
                when (change) {
                    is Put -> rows[keyOf(change.row)] = change.row
                    is Remove -> rows.values.removeAll(change.match::matches)
                }
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock { rows.clear() }
    }
}
