package io.kmpkt.example.modulith.todos.application.handler

import io.kmpkt.command.CommandHandler
import io.kmpkt.example.modulith.todos.domain.command.AddTodo
import io.kmpkt.example.modulith.todos.domain.model.TodoItem
import io.kmpkt.example.modulith.todos.domain.repository.TodoRepository

class AddTodoHandler(
    private val repository: TodoRepository,
) : CommandHandler<AddTodo> {
    override suspend fun handle(command: AddTodo) {
        repository.save(TodoItem(id = command.id, title = command.title))
    }
}
