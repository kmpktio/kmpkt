package io.kmpkt.bridge.ktor.server

/** Configuration for [KtorServer]. */
public data class KtorServerConfig(
    public val port: Int = 8080,
)
