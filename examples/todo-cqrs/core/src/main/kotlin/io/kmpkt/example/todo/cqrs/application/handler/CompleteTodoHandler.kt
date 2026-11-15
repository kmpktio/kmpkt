package io.kmpkt.example.todo.cqrs.application.handler

import io.kmpkt.command.CommandHandler
import io.kmpkt.event.EventBus
import io.kmpkt.example.todo.cqrs.domain.command.CompleteTodo
import io.kmpkt.example.todo.cqrs.domain.event.TodoCompleted
import io.kmpkt.example.todo.cqrs.domain.repository.TodoRepository

class CompleteTodoHandler(
    private val repository: TodoRepository,
    private val events: EventBus,
) : CommandHandler<CompleteTodo> {
    override suspend fun handle(command: CompleteTodo) {
        val current = repository.find(command.id) ?: error("Todo ${command.id} not found")
        if (!current.completed) {
            repository.save(current.copy(completed = true))
            events.publish(TodoCompleted(id = current.id, title = current.title))
        }
    }
}
