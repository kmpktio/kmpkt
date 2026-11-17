package io.kmpkt.example.todo.es.application.handler

import io.kmpkt.aggregate.repository.AggregateRepository
import io.kmpkt.command.CommandHandler
import io.kmpkt.example.todo.es.domain.aggregate.TodoItem
import io.kmpkt.example.todo.es.domain.command.CompleteTodo
import java.time.Clock

class CompleteTodoHandler(
    private val todos: AggregateRepository<TodoItem>,
    private val clock: Clock,
) : CommandHandler<CompleteTodo> {
    override suspend fun handle(command: CompleteTodo) {
        val todo = todos.get(command.id)
        todo.complete(clock)
        todos.save(todo)
    }
}
