package io.kmpkt.example.todo.cqrs.di

import io.kmpkt.command.CommandHandler
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.event.EventHandler
import io.kmpkt.example.todo.cqrs.TodoApp
import io.kmpkt.example.todo.cqrs.domain.repository.TodoRepository
import io.kmpkt.example.todo.cqrs.outbound.audit.CompletedAuditLog
import io.kmpkt.example.todo.cqrs.outbound.persistence.InMemoryTodoRepository
import io.kmpkt.query.QueryHandler

class TodoCqrsConfiguration : Configuration<TodoApp> {
    override fun ContainerBuilder.configure() {
        // Bind the in-memory adapter to the domain repository port.
        service<InMemoryTodoRepository> { bind<TodoRepository>() }
        service<CompletedAuditLog>()

        // Automatically register handlers and DemoInteractionRunner in this package.
        discover("io.kmpkt.example.todo.cqrs") {
            services<CommandHandler<*>>()
            services<QueryHandler<*, *>>()
            services<EventHandler<*>>()
        }
    }
}
