package io.kmpkt.bridge.clikt.di

import com.github.ajalt.clikt.core.CliktCommand
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.di.compiler.typeRef

public class CliktOptions {
    public var name: String = "app"
    public var defaultCommand: String? = null
    internal val commands = linkedSetOf<TypeRef>()

    public fun command(type: TypeRef) {
        commands += type
    }

    public inline fun <reified T : CliktCommand> command() {
        command(typeRef<T>())
    }
}

public fun ContainerBuilder.clikt(block: CliktOptions.() -> Unit = {}) {
    configure(CliktComponent::class, block)
}
