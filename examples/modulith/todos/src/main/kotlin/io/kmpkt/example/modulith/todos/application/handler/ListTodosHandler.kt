package io.kmpkt.example.modulith.todos.application.handler

import io.kmpkt.example.modulith.todos.domain.model.TodoItem
import io.kmpkt.example.modulith.todos.domain.query.ListTodos
import io.kmpkt.example.modulith.todos.domain.repository.TodoRepository
import io.kmpkt.query.QueryHandler

class ListTodosHandler(
    private val repository: TodoRepository,
) : QueryHandler<ListTodos, List<TodoItem>> {
    override suspend fun handle(query: ListTodos): List<TodoItem> = repository.findAll()
}
