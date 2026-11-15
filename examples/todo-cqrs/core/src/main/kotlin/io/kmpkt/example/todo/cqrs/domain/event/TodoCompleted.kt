package io.kmpkt.example.todo.cqrs.domain.event

import io.kmpkt.event.Event

data class TodoCompleted(
    val id: String,
    val title: String,
) : Event
