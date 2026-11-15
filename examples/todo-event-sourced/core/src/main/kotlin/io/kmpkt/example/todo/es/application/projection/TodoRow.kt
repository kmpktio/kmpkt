package io.kmpkt.example.todo.es.application.projection

import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.example.todo.es.domain.aggregate.TodoItem

data class TodoRow(
    val id: AggregateId<TodoItem>,
    val title: String,
    val completed: Boolean = false,
    val pinned: Boolean = false,
)
