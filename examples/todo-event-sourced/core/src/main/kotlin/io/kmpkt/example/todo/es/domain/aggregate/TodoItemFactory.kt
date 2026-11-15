package io.kmpkt.example.todo.es.domain.aggregate

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.factory.AggregateFactory

class TodoItemFactory : AggregateFactory<TodoItem> {
    override fun fromHistory(history: List<DomainEvent<TodoItem>>): TodoItem = TodoItem.fromHistory(history)
}
