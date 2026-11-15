package io.kmpkt.example.todo.cqrs.outbound.persistence

import io.kmpkt.example.todo.cqrs.domain.model.TodoItem
import io.kmpkt.example.todo.cqrs.domain.repository.TodoRepository

class InMemoryTodoRepository : TodoRepository {
    private val items = linkedMapOf<String, TodoItem>()

    override fun save(item: TodoItem) {
        items[item.id] = item
    }

    override fun find(id: String): TodoItem? = items[id]

    override fun findAll(): List<TodoItem> = items.values.toList()
}
