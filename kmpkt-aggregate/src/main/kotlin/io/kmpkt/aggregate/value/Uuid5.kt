package io.kmpkt.aggregate.value

import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * RFC 4122 version 5 (SHA-1 name-based) UUID: the same (namespace, name) pair always maps to the same UUID.
 * The JDK/Kotlin stdlib have no built-in generator for it. Pick your own namespace per kind of name you hash —
 * reusing one namespace for different kinds of names (e.g. list ids and category ids) risks collisions between
 * them.
 */
@OptIn(ExperimentalUuidApi::class)
public fun Uuid.Companion.v5(
    namespace: Uuid,
    name: String,
): Uuid {
    val hash =
        MessageDigest
            .getInstance("SHA-1")
            .run {
                update(namespace.toByteArray())
                update(name.toByteArray(Charsets.UTF_8))
                digest()
            }.copyOf(16)
    hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
    hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
    return Uuid.fromByteArray(hash)
}
