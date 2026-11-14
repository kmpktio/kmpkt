package io.kmpkt.persistence.jdbc

import io.kmpkt.persistence.KeyValueStore
import javax.sql.DataSource

internal class JdbcKeyValueStore(
    private val dataSource: DataSource,
    private val namespace: String,
) : KeyValueStore {
    override suspend fun get(key: String): String? =
        dataSource.inTransaction { connection ->
            connection
                .prepareStatement("SELECT payload FROM kv_record WHERE namespace = ? AND record_key = ?")
                .use { statement ->
                    statement.setString(1, namespace)
                    statement.setString(2, key)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getString(1) else null
                    }
                }
        }

    override suspend fun put(
        key: String,
        value: String,
    ) {
        dataSource.inTransaction { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO kv_record (namespace, record_key, payload)
                    VALUES (?, ?, ?)
                    ON CONFLICT (namespace, record_key) DO UPDATE SET payload = excluded.payload
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, namespace)
                    statement.setString(2, key)
                    statement.setString(3, value)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun delete(key: String) {
        dataSource.inTransaction { connection ->
            connection
                .prepareStatement("DELETE FROM kv_record WHERE namespace = ? AND record_key = ?")
                .use { statement ->
                    statement.setString(1, namespace)
                    statement.setString(2, key)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun clear() {
        dataSource.inTransaction { connection ->
            connection
                .prepareStatement("DELETE FROM kv_record WHERE namespace = ?")
                .use { statement ->
                    statement.setString(1, namespace)
                    statement.executeUpdate()
                }
        }
    }
}
