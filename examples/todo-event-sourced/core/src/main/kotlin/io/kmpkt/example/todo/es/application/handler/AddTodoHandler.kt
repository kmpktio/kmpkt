package io.kmpkt.example.todo.es.application.handler

import io.kmpkt.aggregate.repository.AggregateRepository
import io.kmpkt.command.CommandHandler
import io.kmpkt.example.todo.es.domain.aggregate.TodoItem
import io.kmpkt.example.todo.es.domain.command.AddTodo
import java.time.Clock

class AddTodoHandler(
    private val todos: AggregateRepository<TodoItem>,
    private val clock: Clock,
) : CommandHandler<AddTodo> {
    override suspend fun handle(command: AddTodo) {
        todos.save(TodoItem.create(command.id, command.title, clock))
    }
}
