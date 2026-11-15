package io.kmpkt.example.todo.es.domain.event

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.example.todo.es.domain.aggregate.TodoItem
import kotlinx.serialization.Serializable

@Serializable
sealed interface TodoEvent : DomainEvent<TodoItem>
