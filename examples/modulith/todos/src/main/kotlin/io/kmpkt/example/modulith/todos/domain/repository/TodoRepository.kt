package io.kmpkt.example.modulith.todos.domain.repository

import io.kmpkt.example.modulith.todos.domain.model.TodoItem

interface TodoRepository {
    fun save(item: TodoItem)

    fun find(id: String): TodoItem?

    fun findAll(): List<TodoItem>
}
