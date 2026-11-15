package io.kmpkt.example.modulith.features.di

import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.literal
import io.kmpkt.example.modulith.features.outbound.feature.PrefixCompletedTodoRepository
import io.kmpkt.example.modulith.todos.domain.repository.TodoRepository

class FeatureFlagsOptions {
    var completedPrefix: String? = null
}

fun ContainerBuilder.featureFlags(block: FeatureFlagsOptions.() -> Unit) {
    configure(FeatureFlagsComponent::class, block)
}

class FeatureFlagsComponent : Component<FeatureFlagsOptions> {
    override fun defaults(): FeatureFlagsOptions = FeatureFlagsOptions()

    override fun ContainerBuilder.register(config: FeatureFlagsOptions) {
        val prefix = config.completedPrefix ?: return
        service<PrefixCompletedTodoRepository> {
            decorates<TodoRepository>(priority = 30)
            argument("prefix", literal(prefix))
        }
    }
}
