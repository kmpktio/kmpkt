package io.kmpkt.example.todo.cqrs.domain.repository

import io.kmpkt.example.todo.cqrs.domain.model.TodoItem

interface TodoRepository {
    fun save(item: TodoItem)

    fun find(id: String): TodoItem?

    fun findAll(): List<TodoItem>
}
