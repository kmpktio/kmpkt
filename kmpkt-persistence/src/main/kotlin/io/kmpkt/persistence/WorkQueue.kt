package io.kmpkt.persistence

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * A claimed item from [WorkQueue].
 */
public data class QueuedItem(
    val id: String,
    val payload: String,
    val availableAt: Instant,
    val attempts: Int,
)

/**
 * Persistent work queue supporting delayed delivery, claiming, acknowledgement, requeuing, and dead-lettering.
 */
public interface WorkQueue {
    public suspend fun setup() {}

    public suspend fun enqueue(
        id: String,
        payload: String,
        availableAt: Instant,
    )

    public suspend fun claim(
        now: Instant,
        limit: Int = 1,
    ): List<QueuedItem>

    public suspend fun ack(id: String)

    public suspend fun nack(
        id: String,
        availableAt: Instant,
    )

    public suspend fun fail(id: String)

    public fun list(): Flow<QueuedItem>

    public suspend fun find(id: String): QueuedItem?

    public suspend fun remove(id: String): Boolean
}
