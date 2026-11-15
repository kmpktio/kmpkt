package io.kmpkt.example.modulith.telemetry.di

import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.literal
import io.kmpkt.example.modulith.telemetry.outbound.telemetry.SpanRecorder
import io.kmpkt.example.modulith.telemetry.outbound.telemetry.TracingTodoRepository
import io.kmpkt.example.modulith.todos.domain.repository.TodoRepository

class TelemetryOptions {
    var serviceName: String = "app"
    var enabled: Boolean = true
}

fun ContainerBuilder.telemetry(block: TelemetryOptions.() -> Unit) {
    configure(TelemetryComponent::class, block)
}

class TelemetryComponent : Component<TelemetryOptions> {
    override fun defaults(): TelemetryOptions = TelemetryOptions()

    override fun ContainerBuilder.register(config: TelemetryOptions) {
        service<SpanRecorder>()
        if (config.enabled) {
            service<TracingTodoRepository> {
                decorates<TodoRepository>(priority = 20)
                argument("serviceName", literal(config.serviceName))
            }
        }
    }
}
