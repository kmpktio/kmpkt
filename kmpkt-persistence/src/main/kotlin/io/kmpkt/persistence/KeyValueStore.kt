package io.kmpkt.persistence

/**
 * Key-value storage for opaque string payloads (bookkeeping state, cursors, serialized records).
 */
public interface KeyValueStore {
    public suspend fun get(key: String): String?

    public suspend fun put(
        key: String,
        value: String,
    )

    public suspend fun delete(key: String)

    public suspend fun clear()
}
