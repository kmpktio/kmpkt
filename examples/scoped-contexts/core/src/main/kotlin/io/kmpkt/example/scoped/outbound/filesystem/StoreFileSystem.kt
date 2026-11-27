package io.kmpkt.example.scoped.outbound.filesystem

import io.kmpkt.example.scoped.application.context.StoreConfiguration
import java.util.concurrent.ConcurrentHashMap

/** Store-isolated in-memory filesystem rooted at [rootDirectory]. */
class StoreFileSystem(
    val rootDirectory: String,
) {
    private val files = ConcurrentHashMap<String, String>()

    fun write(
        relativePath: String,
        content: String,
    ) {
        files["$rootDirectory/$relativePath"] = content
    }

    fun read(relativePath: String): String? = files["$rootDirectory/$relativePath"]

    companion object {
        /** Creates a [StoreFileSystem] scoped to the store's configured directory. */
        fun create(config: StoreConfiguration): StoreFileSystem =
            StoreFileSystem(config.storageDirectory)
    }
}
