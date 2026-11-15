package io.kmpkt.bridge.clikt.di

import com.github.ajalt.clikt.core.CliktCommand
import io.kmpkt.bridge.clikt.command.CliktRootCommand
import io.kmpkt.di.ApplicationRunner
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.Tag
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.tagged

public object CliktCommandTag : Tag

public class CliktComponent : Component<CliktOptions> {
    override fun defaults(): CliktOptions = CliktOptions()

    override fun ContainerBuilder.prepare() {
        autoconfigure<CliktCommand> { tag(CliktCommandTag) }
    }

    override fun ContainerBuilder.register(config: CliktOptions) {
        config.commands.forEach { commandType ->
            service(commandType) {
                tag(CliktCommandTag)
            }
        }

        service<CliktRootCommand> {
            bind<ApplicationRunner>()
            argument("name", literal(config.name))
            argument("commands", tagged<CliktCommandTag>(excludeSelf = true))
            config.defaultCommand?.let { argument("defaultCommand", literal(it)) }
        }
    }
}
