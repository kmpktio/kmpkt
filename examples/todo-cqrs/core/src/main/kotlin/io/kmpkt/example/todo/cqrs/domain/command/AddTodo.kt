package io.kmpkt.example.todo.cqrs.domain.command

import io.kmpkt.command.Command

data class AddTodo(
    val id: String,
    val title: String,
) : Command
