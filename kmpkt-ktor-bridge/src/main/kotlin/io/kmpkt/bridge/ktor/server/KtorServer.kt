package io.kmpkt.bridge.ktor.server

import io.kmpkt.bridge.ktor.routing.HttpRoutes
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing

/**
 * Configures and runs an embedded Ktor server from discovered [plugins] and [routes].
 */
public class KtorServer(
    public val config: KtorServerConfig = KtorServerConfig(),
    private val routes: Lazy<List<HttpRoutes>> = lazyOf(emptyList()),
    private val plugins: Lazy<List<KtorServerPlugin>> = lazyOf(emptyList()),
) {
    @Volatile
    private var engine: EmbeddedServer<*, *>? = null

    public fun start(
        port: Int = config.port,
        wait: Boolean = true,
    ) {
        engine = embeddedServer(Netty, port = port, module = ::configure).also { it.start(wait = wait) }
    }

    public fun stop(
        gracePeriodMillis: Long = 500,
        timeoutMillis: Long = 1_000,
    ) {
        engine?.stop(gracePeriodMillis, timeoutMillis)
        engine = null
    }

    public fun configure(application: Application) {
        plugins.value.forEach { with(it) { application.install() } }
        application.routing {
            routes.value.forEach { with(it) { install() } }
        }
    }
}
