package io.kmpkt.example.todo.cqrs.domain.command

import io.kmpkt.command.Command

data class CompleteTodo(
    val id: String,
) : Command
