package io.kmpkt.example.modulith.todos.di

import io.kmpkt.command.CommandHandler
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.example.modulith.todos.domain.repository.TodoRepository
import io.kmpkt.example.modulith.todos.outbound.persistence.InMemoryTodoRepository
import io.kmpkt.query.QueryHandler

class TodosComponent : SimpleComponent {
    override fun ContainerBuilder.register() {
        service<InMemoryTodoRepository> { bind<TodoRepository>() }

        discover("io.kmpkt.example.modulith.todos") {
            services<CommandHandler<*>>()
            services<QueryHandler<*, *>>()
        }
    }
}
