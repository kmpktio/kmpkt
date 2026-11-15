package io.kmpkt.serialization.kotlinx

import kotlinx.serialization.json.Json

/** JSON settings for stored events and snapshots. */
public val persistenceJson: Json =
    Json {
        classDiscriminator = "type"
        encodeDefaults = true
        // A record written by an older schema version may carry fields that no longer exist.
        ignoreUnknownKeys = true
    }

/** Factory for [persistenceJson] used by DI container defaults. */
public fun createPersistenceJson(): Json = persistenceJson
