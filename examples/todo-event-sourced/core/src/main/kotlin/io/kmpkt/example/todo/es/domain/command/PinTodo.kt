package io.kmpkt.example.todo.es.domain.command

import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.command.Command
import io.kmpkt.example.todo.es.domain.aggregate.TodoItem

data class PinTodo(
    val id: AggregateId<TodoItem>,
) : Command
