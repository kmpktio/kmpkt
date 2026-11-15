package io.kmpkt.example.modulith.todos.domain.model

data class TodoItem(
    val id: String,
    val title: String,
    val completed: Boolean = false,
)
