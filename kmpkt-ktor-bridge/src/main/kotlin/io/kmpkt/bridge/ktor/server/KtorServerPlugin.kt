package io.kmpkt.bridge.ktor.server

import io.ktor.server.application.Application

/** Application-level Ktor configuration (plugins, static resources, error mapping). */
public fun interface KtorServerPlugin {
    public fun Application.install()
}
