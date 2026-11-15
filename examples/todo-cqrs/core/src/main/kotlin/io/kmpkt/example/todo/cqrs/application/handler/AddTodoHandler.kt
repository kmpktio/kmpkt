package io.kmpkt.example.todo.cqrs.application.handler

import io.kmpkt.command.CommandHandler
import io.kmpkt.example.todo.cqrs.domain.command.AddTodo
import io.kmpkt.example.todo.cqrs.domain.model.TodoItem
import io.kmpkt.example.todo.cqrs.domain.repository.TodoRepository

class AddTodoHandler(
    private val repository: TodoRepository,
) : CommandHandler<AddTodo> {
    override suspend fun handle(command: AddTodo) {
        repository.save(TodoItem(id = command.id, title = command.title))
    }
}
