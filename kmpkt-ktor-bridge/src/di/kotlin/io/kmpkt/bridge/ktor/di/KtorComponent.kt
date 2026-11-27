package io.kmpkt.bridge.ktor.di

import io.kmpkt.bridge.ktor.routing.HttpRoutes
import io.kmpkt.bridge.ktor.server.KtorServer
import io.kmpkt.bridge.ktor.server.KtorServerConfig
import io.kmpkt.bridge.ktor.server.KtorServerPlugin
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.Tag
import io.kmpkt.di.compiler.tagged as taggedArgument

public object HttpRoutesTag : Tag

public object KtorPluginTag : Tag

public class KtorComponent : Component<KtorOptions> {
    override fun defaults(): KtorOptions = KtorOptions()

    override fun ContainerBuilder.prepare() {
        autoconfigure<HttpRoutes> { tag(HttpRoutesTag) }
        autoconfigure<KtorServerPlugin> { tag(KtorPluginTag) }
    }

    override fun ContainerBuilder.register(config: KtorOptions) {
        service<KtorServerConfig> {
            argument("port", config.port)
        }
        config.modules.forEachIndexed { index, fn ->
            factory<KtorServerPlugin>(fn, ServiceId("ktor.module.$index")) {
                tag(KtorPluginTag)
            }
        }
        service<KtorServer> {
            argument("routes", taggedArgument<HttpRoutesTag>())
            argument("plugins", taggedArgument<KtorPluginTag>())
        }
    }
}
