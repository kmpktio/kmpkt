package io.kmpkt.example.modulith.todos.domain.command

import io.kmpkt.command.Command

data class CompleteTodo(
    val id: String,
) : Command
