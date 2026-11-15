package io.kmpkt.example.modulith.features.outbound.feature

import io.kmpkt.example.modulith.todos.domain.model.TodoItem
import io.kmpkt.example.modulith.todos.domain.repository.TodoRepository

class PrefixCompletedTodoRepository(
    private val delegate: TodoRepository,
    private val prefix: String,
) : TodoRepository by delegate {
    override fun save(item: TodoItem) {
        val updated =
            if (item.completed && !item.title.startsWith(prefix)) {
                item.copy(title = "$prefix${item.title}")
            } else {
                item
            }
        delegate.save(updated)
    }
}
