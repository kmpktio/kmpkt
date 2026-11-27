package io.kmpkt.example.scoped.outbound.database

import io.kmpkt.example.scoped.application.context.StoreConfiguration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Store-isolated in-memory database bound to [schema]. */
class StoreDatabase(
    val schema: String,
) {
    private val tables = ConcurrentHashMap<String, CopyOnWriteArrayList<Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> table(name: String): MutableList<T> =
        tables.computeIfAbsent("$schema.$name") { CopyOnWriteArrayList() } as MutableList<T>

    companion object {
        /** Creates a [StoreDatabase] scoped to the store's configured database schema. */
        fun create(config: StoreConfiguration): StoreDatabase =
            StoreDatabase(config.databaseSchema)
    }
}
