package io.kmpkt.persistence.jdbc

import io.kmpkt.persistence.AppendLog
import io.kmpkt.persistence.KeyValueStore
import io.kmpkt.persistence.Persistence
import io.kmpkt.persistence.WorkQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.sql.DataSource

/**
 * Durable [Persistence] backed by a JDBC [DataSource].
 */
public class JdbcPersistence(
    private val dataSource: DataSource,
) : Persistence {
    private val coroutineLocks = mutableMapOf<String, Mutex>()
    private val guard = Mutex()
    private val schemaFuse = Fuse()

    private val advisoryLocks: Boolean by lazy {
        dataSource.connection.use { it.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true) }
    }

    override fun keyValueStore(name: String): KeyValueStore = JdbcKeyValueStore(dataSource, name)

    override fun appendLog(name: String): AppendLog = JdbcAppendLog(dataSource, name)

    override fun workQueue(name: String): WorkQueue = JdbcWorkQueue(dataSource, name, schemaFuse)

    override suspend fun setup() {
        schemaFuse.runOnce {
            withContext(Dispatchers.IO) {
                PersistenceSchema.migrate(dataSource)
            }
        }
    }

    override suspend fun <R> inTransaction(block: suspend () -> R): R = dataSource.inTransaction { block() }

    override suspend fun <R> underLock(
        name: String,
        block: suspend () -> R,
    ): R? =
        if (advisoryLocks) {
            underAdvisoryLock(name, block)
        } else {
            underCoroutineLock(name, block)
        }

    private suspend fun <R> underAdvisoryLock(
        name: String,
        block: suspend () -> R,
    ): R? {
        val connection = withContext(Dispatchers.IO) { dataSource.connection }

        return try {
            val acquired =
                connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
                    statement.setLong(1, name.asLockKey())
                    statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                }

            if (!acquired) null else block()
        } finally {
            withContext(Dispatchers.IO) { connection.close() }
        }
    }

    private suspend fun <R> underCoroutineLock(
        name: String,
        block: suspend () -> R,
    ): R? {
        val lock = guard.withLock { coroutineLocks.getOrPut(name) { Mutex() } }
        if (!lock.tryLock()) return null
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private fun String.asLockKey(): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return ByteBuffer.wrap(digest).getLong()
}
