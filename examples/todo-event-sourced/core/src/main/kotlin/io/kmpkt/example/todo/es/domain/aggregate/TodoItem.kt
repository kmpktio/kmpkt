package io.kmpkt.example.todo.es.domain.aggregate

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.model.BaseAggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.example.todo.es.domain.event.TodoAdded
import io.kmpkt.example.todo.es.domain.event.TodoCompleted
import io.kmpkt.example.todo.es.domain.event.TodoPinned
import java.time.Instant

class TodoItem internal constructor(
    id: AggregateId<TodoItem>,
) : BaseAggregateRoot<TodoItem>(id) {
    var title: String = ""
        private set
    var completed: Boolean = false
        private set
    var pinned: Boolean = false
        private set

    fun complete(now: Instant = Instant.now()) {
        if (!completed) {
            recordThat(TodoCompleted(id, version + 1, now))
        }
    }

    fun pin(now: Instant = Instant.now()) {
        if (!pinned) {
            recordThat(TodoPinned(id, version + 1, now))
        }
    }

    override fun updateState(event: DomainEvent<TodoItem>) {
        when (event) {
            is TodoAdded -> title = event.title
            is TodoCompleted -> completed = true
            is TodoPinned -> pinned = true
            else -> error("Unsupported event: ${event::class}")
        }
    }

    companion object {
        fun create(
            id: AggregateId<TodoItem>,
            title: String,
            now: Instant = Instant.now(),
        ): TodoItem =
            TodoItem(id).apply {
                recordThat(TodoAdded(id, version = 1, occurredAt = now, title = title))
            }

        internal fun fromHistory(history: List<DomainEvent<TodoItem>>): TodoItem =
            TodoItem(history.first().aggregate).apply { history.forEach(::replay) }
    }
}
