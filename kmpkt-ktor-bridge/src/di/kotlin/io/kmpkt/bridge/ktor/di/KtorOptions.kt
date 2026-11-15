package io.kmpkt.bridge.ktor.di

import io.kmpkt.bridge.ktor.server.KtorServerPlugin
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.literal
import kotlin.reflect.KFunction

public class KtorOptions {
    public var port: Argument = literal(8080)
    internal val modules = mutableListOf<KFunction<KtorServerPlugin>>()

    public fun module(fn: KFunction<KtorServerPlugin>) {
        modules += fn
    }
}

public fun ContainerBuilder.ktor(block: KtorOptions.() -> Unit = {}) {
    configure(KtorComponent::class, block)
}
