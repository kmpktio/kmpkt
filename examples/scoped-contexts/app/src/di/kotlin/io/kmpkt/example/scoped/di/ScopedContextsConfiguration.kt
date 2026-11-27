package io.kmpkt.example.scoped.di

import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.example.scoped.ScopedContextsApp

/** Application configuration wiring the app runner; `:core`, `:catalog`, and `:orders` register via components. */
class ScopedContextsConfiguration : Configuration<ScopedContextsApp> {
    override fun ContainerBuilder.configure() {
        discover("io.kmpkt.example.scoped")
    }
}
