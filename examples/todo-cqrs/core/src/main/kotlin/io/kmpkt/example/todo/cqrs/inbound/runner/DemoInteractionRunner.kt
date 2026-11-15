package io.kmpkt.example.todo.cqrs.inbound.runner

import io.kmpkt.command.CommandBus
import io.kmpkt.di.ApplicationRunner
import io.kmpkt.example.todo.cqrs.domain.command.AddTodo
import io.kmpkt.example.todo.cqrs.domain.command.CompleteTodo
import io.kmpkt.example.todo.cqrs.domain.model.TodoItem
import io.kmpkt.example.todo.cqrs.domain.query.ListTodos
import io.kmpkt.example.todo.cqrs.outbound.audit.CompletedAuditLog
import io.kmpkt.query.QueryBus
import kotlinx.coroutines.runBlocking

/** Simulates a client interacting with the application by dispatching commands and querying the result. */
class DemoInteractionRunner(
    private val commands: CommandBus,
    private val queries: QueryBus,
    private val auditLog: CompletedAuditLog,
) : ApplicationRunner {
    override fun run(args: Array<String>) =
        runBlocking {
            val firstTitle = args.firstOrNull() ?: "Buy milk"

            // 1. Write path: dispatch fire-and-forget commands; CompleteTodo also publishes TodoCompleted.
            commands.dispatch(AddTodo(id = "1", title = firstTitle))
            commands.dispatch(AddTodo(id = "2", title = "Write tests"))
            commands.dispatch(CompleteTodo(id = "1"))

            // 2. Read path: query the current state through the dedicated QueryBus.
            val items = queries.ask(ListTodos)
            check(
                items ==
                    listOf(
                        TodoItem(id = "1", title = firstTitle, completed = true),
                        TodoItem(id = "2", title = "Write tests", completed = false),
                    ),
            ) {
                "Unexpected todo list: $items"
            }

            // 3. Verify side effect: CompletedAuditLog subscribed to TodoCompleted on the EventBus.
            check(auditLog.entries == listOf("completed todo #1: $firstTitle")) {
                "Unexpected completed audit entries: ${auditLog.entries}"
            }

            println("Todos: $items | Completed audit: ${auditLog.entries}")
        }
}
