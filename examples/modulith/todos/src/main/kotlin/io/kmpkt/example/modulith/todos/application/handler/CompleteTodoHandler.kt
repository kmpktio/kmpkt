package io.kmpkt.example.modulith.todos.application.handler

import io.kmpkt.command.CommandHandler
import io.kmpkt.example.modulith.todos.domain.command.CompleteTodo
import io.kmpkt.example.modulith.todos.domain.repository.TodoRepository

class CompleteTodoHandler(
    private val repository: TodoRepository,
) : CommandHandler<CompleteTodo> {
    override suspend fun handle(command: CompleteTodo) {
        val item = repository.find(command.id) ?: error("Todo ${command.id} not found")
        repository.save(item.copy(completed = true))
    }
}
