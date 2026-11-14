package io.kmpkt.persistence.jdbc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal class JdbcTransactionContext(
    val connection: Connection,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<JdbcTransactionContext>
}

/**
 * Runs [block] in one transaction on the IO dispatcher, reusing any active [JdbcTransactionContext] in the
 * caller's coroutine context. Rolls back on any failure when this call owns the transaction.
 */
internal suspend fun <Result> DataSource.inTransaction(block: suspend (Connection) -> Result): Result {
    val active = coroutineContext[JdbcTransactionContext]
    if (active != null) {
        return withContext(Dispatchers.IO) { block(active.connection) }
    }

    return withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.autoCommit = false
            try {
                withContext(JdbcTransactionContext(connection)) {
                    block(connection)
                }.also { connection.commit() }
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }
}
