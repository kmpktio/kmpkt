package io.kmpkt.example.modulith.di

import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.example.modulith.ModulithApp
import io.kmpkt.example.modulith.features.di.featureFlags
import io.kmpkt.example.modulith.telemetry.di.telemetry

class ModulithConfiguration : Configuration<ModulithApp> {
    override fun ContainerBuilder.configure() {
        // Enable module decorators around TodoRepository (outer-to-inner: features 30 -> telemetry 20 -> audit 10).
        telemetry {
            serviceName = "todo-modulith"
        }
        featureFlags {
            completedPrefix = "[DONE] "
        }
    }
}
