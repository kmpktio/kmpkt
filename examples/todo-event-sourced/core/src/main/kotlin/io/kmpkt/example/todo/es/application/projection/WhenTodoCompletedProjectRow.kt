package io.kmpkt.example.todo.es.application.projection

import io.kmpkt.example.todo.es.domain.event.TodoCompleted
import io.kmpkt.projection.ProjectionChange
import io.kmpkt.projection.ProjectionView
import io.kmpkt.projection.Projector
import io.kmpkt.projection.Put
import io.kmpkt.projection.match

class WhenTodoCompletedProjectRow : Projector<TodoCompleted, TodoRow> {
    override suspend fun project(
        event: TodoCompleted,
        view: ProjectionView<TodoRow>,
    ): List<ProjectionChange<TodoRow>> =
        view
            .findAll(match(TodoRow::id to event.aggregate))
            .map { Put(it.copy(completed = true)) }
}
