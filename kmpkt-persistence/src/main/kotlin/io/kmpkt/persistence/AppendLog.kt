package io.kmpkt.persistence

import java.time.Instant

/**
 * An entry to append to a stream in [AppendLog].
 */
public data class NewLogEntry(
    val id: String,
    val type: String,
    val payload: String,
    val occurredAt: Instant,
    val checkpoint: Boolean = false,
)

/**
 * A persisted entry in [AppendLog], ordered globally by [position] and within its stream by [version].
 */
public data class LogEntry(
    val position: Long,
    val id: String,
    val streamType: String,
    val streamId: String,
    val version: Long,
    val type: String,
    val payload: String,
    val occurredAt: Instant,
    val checkpoint: Boolean,
)

/**
 * Raised when [AppendLog.append] finds the stream at a version different from [expectedVersion].
 */
public class LogVersionConflictException(
    public val streamType: String,
    public val streamId: String,
    public val expectedVersion: Long,
    cause: Throwable? = null,
) : RuntimeException("Stream $streamType:$streamId is not at expected version $expectedVersion", cause)

/**
 * Global append-only log partitioned into named streams `(streamType, streamId)` with optimistic concurrency
 * checks on [expectedVersion].
 */
public interface AppendLog {
    /**
     * Appends [entries] to `(streamType, streamId)` if its current version equals [expectedVersion].
     *
     * @throws LogVersionConflictException if the stream is at another version.
     */
    public suspend fun append(
        streamType: String,
        streamId: String,
        expectedVersion: Long,
        entries: List<NewLogEntry>,
    )

    /**
     * Reads entries of `(streamType, streamId)` with version greater than [fromVersion], oldest first.
     * When [fromLatestCheckpoint] is true, reading starts at or after the latest checkpoint entry.
     */
    public suspend fun readStream(
        streamType: String,
        streamId: String,
        fromVersion: Long = 0,
        limit: Int? = null,
        fromLatestCheckpoint: Boolean = true,
    ): List<LogEntry>

    /**
     * Reads entries across all streams with [LogEntry.position] greater than [afterPosition], oldest first.
     */
    public suspend fun readAfter(
        afterPosition: Long = 0,
        limit: Int = 100,
        includeCheckpoints: Boolean = false,
    ): List<LogEntry>

    /**
     * Highest global position in the log, or `0` when empty.
     */
    public suspend fun latestPosition(includeCheckpoints: Boolean = false): Long
}
