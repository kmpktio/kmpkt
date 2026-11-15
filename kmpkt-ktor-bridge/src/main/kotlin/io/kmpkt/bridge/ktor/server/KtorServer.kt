package io.kmpkt.bridge.ktor.server

import io.kmpkt.bridge.ktor.routing.HttpRoutes
import io.kmpkt.worker.ManagedWorker
import io.kmpkt.worker.UnmanagedWorker
import io.kmpkt.worker.Worker
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Configures and runs an embedded Ktor server from discovered [plugins], [routes], and [workers].
 */
public class KtorServer(
    private val config: KtorServerConfig = KtorServerConfig(),
    private val routes: Lazy<List<HttpRoutes>> = lazyOf(emptyList()),
    private val workers: Lazy<List<Worker>> = lazyOf(emptyList()),
    private val plugins: Lazy<List<KtorServerPlugin>> = lazyOf(emptyList()),
) {
    public fun start(
        port: Int = config.port,
        wait: Boolean = true,
    ) {
        embeddedServer(Netty, port = port) {
            configure(this)
            monitor.subscribe(ApplicationStarted) { application ->
                workers.value.forEach { worker ->
                    when (worker) {
                        is ManagedWorker -> worker.start()
                        is UnmanagedWorker -> application.launch { worker.run() }
                    }
                }
            }
            monitor.subscribe(ApplicationStopping) {
                val managed = workers.value.filterIsInstance<ManagedWorker>()
                if (managed.isNotEmpty()) {
                    runBlocking { managed.forEach { it.stop() } }
                }
            }
        }.start(wait = wait)
    }

    public fun configure(application: Application) {
        plugins.value.forEach { with(it) { application.install() } }
        application.routing {
            routes.value.forEach { with(it) { install() } }
        }
    }
}
