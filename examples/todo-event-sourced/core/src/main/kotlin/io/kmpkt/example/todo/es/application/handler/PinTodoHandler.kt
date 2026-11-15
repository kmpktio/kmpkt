package io.kmpkt.example.todo.es.application.handler

import io.kmpkt.aggregate.repository.AggregateRepository
import io.kmpkt.command.CommandHandler
import io.kmpkt.example.todo.es.domain.aggregate.TodoItem
import io.kmpkt.example.todo.es.domain.command.PinTodo

class PinTodoHandler(
    private val todos: AggregateRepository<TodoItem>,
) : CommandHandler<PinTodo> {
    override suspend fun handle(command: PinTodo) {
        val todo = todos.get(command.id)
        todo.pin()
        todos.save(todo)
    }
}
