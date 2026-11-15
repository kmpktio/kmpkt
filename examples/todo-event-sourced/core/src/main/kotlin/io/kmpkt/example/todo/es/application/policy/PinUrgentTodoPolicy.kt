package io.kmpkt.example.todo.es.application.policy

import io.kmpkt.command.Command
import io.kmpkt.example.todo.es.domain.command.PinTodo
import io.kmpkt.example.todo.es.domain.event.TodoAdded
import io.kmpkt.policy.Policy

class PinUrgentTodoPolicy : Policy<TodoAdded> {
    override fun decide(event: TodoAdded): List<Command> =
        if (event.title.endsWith("!")) listOf(PinTodo(event.aggregate)) else emptyList()
}
