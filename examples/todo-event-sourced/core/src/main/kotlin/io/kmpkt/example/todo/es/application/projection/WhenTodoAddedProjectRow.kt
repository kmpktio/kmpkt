package io.kmpkt.example.todo.es.application.projection

import io.kmpkt.example.todo.es.domain.event.TodoAdded
import io.kmpkt.projection.ProjectionChange
import io.kmpkt.projection.ProjectionView
import io.kmpkt.projection.Projector
import io.kmpkt.projection.Put

class WhenTodoAddedProjectRow : Projector<TodoAdded, TodoRow> {
    override suspend fun project(
        event: TodoAdded,
        view: ProjectionView<TodoRow>,
    ): List<ProjectionChange<TodoRow>> = listOf(Put(TodoRow(id = event.aggregate, title = event.title)))
}
