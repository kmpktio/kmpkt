package io.kmpkt.example.todo.es.di

import io.kmpkt.aggregate.di.aggregate
import io.kmpkt.aggregate.factory.AggregateFactory
import io.kmpkt.command.CommandHandler
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.service
import io.kmpkt.example.todo.es.TodoEventSourcedApp
import io.kmpkt.example.todo.es.application.projection.TodoRow
import io.kmpkt.example.todo.es.domain.aggregate.TodoItem
import io.kmpkt.example.todo.es.domain.aggregate.TodoItemFactory
import io.kmpkt.example.todo.es.inbound.runner.DemoInteractionRunner
import io.kmpkt.example.todo.es.outbound.persistence.todoRowStore
import io.kmpkt.example.todo.es.outbound.serialization.todoEventSerializer
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.policy.Policy
import io.kmpkt.policy.di.policy
import io.kmpkt.projection.ProjectionStore
import io.kmpkt.projection.ProjectionView
import io.kmpkt.projection.Projector
import io.kmpkt.projection.di.projection
import io.kmpkt.query.QueryHandler

class TodoEventSourcedConfiguration : Configuration<TodoEventSourcedApp> {
    override fun ContainerBuilder.configure() {
        // 1. Register event-sourced aggregate stream, read-model projection, and reactive policy engine.
        aggregate {
            aggregate<TodoItem>("todo_item")
        }
        projection {
            projection<TodoRow>("todos")
        }
        policy()

        // 2. Provide event serialization, aggregate hydration, and the projection store/view.
        factory(::todoEventSerializer)
        service<TodoItemFactory> { bind<AggregateFactory<TodoItem>>() }
        factory(::todoRowStore) {
            bind<ProjectionStore<TodoRow>>()
            bind<ProjectionView<TodoRow>>()
        }

        // 3. Wire the demo interaction runner with the qualified "command" message bus.
        service<DemoInteractionRunner> {
            argument("commandMessageBus", service<MessageBus>("command"))
        }

        // 4. Discover handlers, policies, and projectors automatically.
        discover("io.kmpkt.example.todo.es") {
            services<CommandHandler<*>>()
            services<QueryHandler<*, *>>()
            services<Policy<*>>()
            services<Projector<*, *>>()
        }
    }
}
