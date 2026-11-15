package io.kmpkt.bridge.ktor.routing

import io.ktor.server.routing.Routing

/** Routes contributed by an HTTP adapter. */
public fun interface HttpRoutes {
    public fun Routing.install()
}
