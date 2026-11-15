package io.kmpkt.example.todo.es.domain.command

import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.command.Command
import io.kmpkt.example.todo.es.domain.aggregate.TodoItem

data class AddTodo(
    val id: AggregateId<TodoItem> = AggregateUuid.generate(),
    val title: String,
) : Command
