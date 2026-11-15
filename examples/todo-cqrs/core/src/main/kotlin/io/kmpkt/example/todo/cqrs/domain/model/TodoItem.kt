package io.kmpkt.example.todo.cqrs.domain.model

data class TodoItem(
    val id: String,
    val title: String,
    val completed: Boolean = false,
)
