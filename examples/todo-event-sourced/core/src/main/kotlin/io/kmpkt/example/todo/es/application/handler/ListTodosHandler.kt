package io.kmpkt.example.todo.es.application.handler

import io.kmpkt.example.todo.es.application.projection.TodoRow
import io.kmpkt.example.todo.es.domain.query.ListTodos
import io.kmpkt.projection.ProjectionView
import io.kmpkt.query.QueryHandler

class ListTodosHandler(
    private val view: ProjectionView<TodoRow>,
) : QueryHandler<ListTodos, List<TodoRow>> {
    override suspend fun handle(query: ListTodos): List<TodoRow> = view.findAll()
}
