package io.kmpkt.persistence.jdbc

import io.kmpkt.persistence.QueuedItem
import io.kmpkt.persistence.WorkQueue
import io.kmpkt.resilience.Fuse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.sql.DataSource

internal class JdbcWorkQueue(
    private val dataSource: DataSource,
    private val queue: String,
    private val schemaFuse: Fuse = Fuse(),
) : WorkQueue {
    override suspend fun setup() {
        schemaFuse.runOnce {
            withContext(Dispatchers.IO) {
                PersistenceSchema.migrate(dataSource)
            }
        }
    }

    override suspend fun enqueue(
        id: String,
        payload: String,
        availableAt: Instant,
    ) {
        dataSource.inTransaction { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO work_queue (id, queue, payload, available_at, attempts, status)
                    VALUES (?, ?, ?, ?, 0, 'PENDING')
                    ON CONFLICT (queue, id) DO UPDATE SET
                        payload = excluded.payload,
                        available_at = excluded.available_at,
                        attempts = 0,
                        status = 'PENDING'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, id)
                    statement.setString(2, queue)
                    statement.setString(3, payload)
                    statement.setString(4, availableAt.toString())
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun claim(
        now: Instant,
        limit: Int,
    ): List<QueuedItem> =
        dataSource.inTransaction { connection ->
            val candidates =
                connection
                    .prepareStatement(
                        """
                        SELECT id, payload, available_at, attempts
                        FROM work_queue
                        WHERE queue = ? AND status = 'PENDING' AND available_at <= ?
                        ORDER BY available_at
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, queue)
                        statement.setString(2, now.toString())
                        statement.setInt(3, limit)
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    add(
                                        QueuedItem(
                                            id = rows.getString("id"),
                                            payload = rows.getString("payload"),
                                            availableAt = Instant.parse(rows.getString("available_at")),
                                            attempts = rows.getInt("attempts") + 1,
                                        ),
                                    )
                                }
                            }
                        }
                    }

            if (candidates.isEmpty()) return@inTransaction emptyList()

            connection
                .prepareStatement(
                    "UPDATE work_queue SET status = 'CLAIMED', attempts = attempts + 1" +
                        " WHERE queue = ? AND id = ? AND status = 'PENDING'",
                ).use { update ->
                    candidates.filter { item ->
                        update.setString(1, queue)
                        update.setString(2, item.id)
                        update.executeUpdate() == 1
                    }
                }
        }

    override suspend fun ack(id: String) {
        dataSource.inTransaction { connection ->
            connection.prepareStatement("DELETE FROM work_queue WHERE queue = ? AND id = ?").use { statement ->
                statement.setString(1, queue)
                statement.setString(2, id)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun nack(
        id: String,
        availableAt: Instant,
    ) {
        dataSource.inTransaction { connection ->
            connection
                .prepareStatement("UPDATE work_queue SET status = 'PENDING', available_at = ? WHERE queue = ? AND id = ?")
                .use { statement ->
                    statement.setString(1, availableAt.toString())
                    statement.setString(2, queue)
                    statement.setString(3, id)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun fail(id: String) {
        dataSource.inTransaction { connection ->
            connection
                .prepareStatement("UPDATE work_queue SET status = 'FAILED' WHERE queue = ? AND id = ?")
                .use { statement ->
                    statement.setString(1, queue)
                    statement.setString(2, id)
                    statement.executeUpdate()
                }
        }
    }
}
