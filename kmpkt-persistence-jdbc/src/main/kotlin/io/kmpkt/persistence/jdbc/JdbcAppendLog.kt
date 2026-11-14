package io.kmpkt.persistence.jdbc

import io.kmpkt.persistence.AppendLog
import io.kmpkt.persistence.LogEntry
import io.kmpkt.persistence.LogVersionConflictException
import io.kmpkt.persistence.NewLogEntry
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

internal class JdbcAppendLog(
    private val dataSource: DataSource,
    private val logName: String,
) : AppendLog {
    override suspend fun append(
        streamType: String,
        streamId: String,
        expectedVersion: Long,
        entries: List<NewLogEntry>,
    ) {
        if (entries.isEmpty()) return
        dataSource.inTransaction { connection ->
            val current = currentVersion(connection, streamType, streamId)
            if (current != expectedVersion) {
                throw LogVersionConflictException(streamType, streamId, expectedVersion)
            }
            var version = current
            try {
                entries.forEach { entry ->
                    version += 1
                    insert(connection, streamType, streamId, version, entry)
                }
            } catch (failure: SQLException) {
                if (failure.isUniqueViolation()) {
                    throw LogVersionConflictException(streamType, streamId, expectedVersion, cause = failure)
                }
                throw failure
            }
        }
    }

    override suspend fun readStream(
        streamType: String,
        streamId: String,
        fromVersion: Long,
        limit: Int?,
        fromLatestCheckpoint: Boolean,
    ): List<LogEntry> =
        dataSource.inTransaction { connection ->
            val checkpointClause =
                if (fromLatestCheckpoint) {
                    " AND version >= COALESCE(" +
                        "(SELECT MAX(version) FROM event WHERE log_name = ? AND aggregate_type = ?" +
                        " AND aggregate_id = ? AND is_snapshot = TRUE), 0)"
                } else {
                    ""
                }
            val limitClause = if (limit == null) "" else " LIMIT ?"
            val sql =
                "SELECT $COLUMNS FROM event WHERE log_name = ? AND aggregate_type = ? AND aggregate_id = ? AND version > ?" +
                    checkpointClause +
                    " ORDER BY version" +
                    limitClause

            connection.prepareStatement(sql).use { statement ->
                var index = 1
                statement.setString(index++, logName)
                statement.setString(index++, streamType)
                statement.setString(index++, streamId)
                statement.setLong(index++, fromVersion)
                if (fromLatestCheckpoint) {
                    statement.setString(index++, logName)
                    statement.setString(index++, streamType)
                    statement.setString(index++, streamId)
                }
                if (limit != null) {
                    statement.setInt(index, limit)
                }
                statement.executeQuery().use(::readEntries)
            }
        }

    override suspend fun readAfter(
        afterPosition: Long,
        limit: Int,
        includeCheckpoints: Boolean,
    ): List<LogEntry> =
        dataSource.inTransaction { connection ->
            val checkpointFilter = if (includeCheckpoints) "" else " AND is_snapshot = FALSE"
            connection
                .prepareStatement(
                    "SELECT $COLUMNS FROM event WHERE log_name = ? AND seq > ?$checkpointFilter ORDER BY seq LIMIT ?",
                ).use { statement ->
                    statement.setString(1, logName)
                    statement.setLong(2, afterPosition)
                    statement.setInt(3, limit)
                    statement.executeQuery().use(::readEntries)
                }
        }

    override suspend fun latestPosition(includeCheckpoints: Boolean): Long =
        dataSource.inTransaction { connection ->
            val checkpointFilter = if (includeCheckpoints) "" else " AND is_snapshot = FALSE"
            connection
                .prepareStatement("SELECT COALESCE(MAX(seq), 0) FROM event WHERE log_name = ?$checkpointFilter")
                .use { statement ->
                    statement.setString(1, logName)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.getLong(1) else 0L
                    }
                }
        }

    private fun currentVersion(
        connection: Connection,
        streamType: String,
        streamId: String,
    ): Long =
        connection
            .prepareStatement(
                "SELECT COALESCE(MAX(version), 0) FROM event WHERE log_name = ? AND aggregate_type = ? AND aggregate_id = ?",
            ).use { statement ->
                statement.setString(1, logName)
                statement.setString(2, streamType)
                statement.setString(3, streamId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getLong(1) else 0L
                }
            }

    private fun insert(
        connection: Connection,
        streamType: String,
        streamId: String,
        version: Long,
        entry: NewLogEntry,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO event (event_id, log_name, aggregate_type, aggregate_id, version, type, payload, occurred_at, is_snapshot, seq)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, entry.id)
                statement.setString(2, logName)
                statement.setString(3, streamType)
                statement.setString(4, streamId)
                statement.setLong(5, version)
                statement.setString(6, entry.type)
                statement.setString(7, entry.payload)
                statement.setString(8, entry.occurredAt.toString())
                statement.setBoolean(9, entry.checkpoint)
                statement.setLong(10, nextPosition(connection))
                statement.executeUpdate()
            }
    }

    private fun nextPosition(connection: Connection): Long =
        connection
            .prepareStatement(
                """
                INSERT INTO event_sequence (name, value) VALUES (?, 1)
                ON CONFLICT (name) DO UPDATE SET value = event_sequence.value + 1
                RETURNING value
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, logName)
                statement.executeQuery().use { result ->
                    check(result.next()) { "event_sequence upsert returned no row" }
                    result.getLong(1)
                }
            }

    private fun readEntries(rows: ResultSet): List<LogEntry> =
        buildList {
            while (rows.next()) {
                add(
                    LogEntry(
                        position = rows.getLong("seq"),
                        id = rows.getString("event_id"),
                        streamType = rows.getString("aggregate_type"),
                        streamId = rows.getString("aggregate_id"),
                        version = rows.getLong("version"),
                        type = rows.getString("type"),
                        payload = rows.getString("payload"),
                        occurredAt = Instant.parse(rows.getString("occurred_at")),
                        checkpoint = rows.getBoolean("is_snapshot"),
                    ),
                )
            }
        }

    private fun SQLException.isUniqueViolation(): Boolean =
        sqlState == SQLITE_CONSTRAINT || sqlState == POSTGRES_UNIQUE_VIOLATION

    private companion object {
        private const val COLUMNS =
            "seq, event_id, aggregate_type, aggregate_id, version, type, payload, occurred_at, is_snapshot"
        private const val SQLITE_CONSTRAINT = "19"
        private const val POSTGRES_UNIQUE_VIOLATION = "23505"
    }
}
