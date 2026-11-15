package io.kmpkt.example.todo.cqrs.application.handler

import io.kmpkt.example.todo.cqrs.domain.model.TodoItem
import io.kmpkt.example.todo.cqrs.domain.query.ListTodos
import io.kmpkt.example.todo.cqrs.domain.repository.TodoRepository
import io.kmpkt.query.QueryHandler

class ListTodosHandler(
    private val repository: TodoRepository,
) : QueryHandler<ListTodos, List<TodoItem>> {
    override suspend fun handle(query: ListTodos): List<TodoItem> = repository.findAll()
}
