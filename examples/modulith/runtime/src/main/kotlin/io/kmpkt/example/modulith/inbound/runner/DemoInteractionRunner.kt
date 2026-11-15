package io.kmpkt.example.modulith.inbound.runner

import io.kmpkt.command.CommandBus
import io.kmpkt.di.ApplicationRunner
import io.kmpkt.example.modulith.audit.outbound.audit.AuditLog
import io.kmpkt.example.modulith.telemetry.outbound.telemetry.SpanRecorder
import io.kmpkt.example.modulith.todos.domain.command.AddTodo
import io.kmpkt.example.modulith.todos.domain.command.CompleteTodo
import io.kmpkt.example.modulith.todos.domain.model.TodoItem
import io.kmpkt.example.modulith.todos.domain.query.ListTodos
import io.kmpkt.query.QueryBus
import kotlinx.coroutines.runBlocking

/** Simulates a client interacting with the modulith and verifies that all module decorators ran. */
class DemoInteractionRunner(
    private val commands: CommandBus,
    private val queries: QueryBus,
    private val auditLog: AuditLog,
    private val spans: SpanRecorder,
) : ApplicationRunner {
    override fun run(args: Array<String>) =
        runBlocking {
            // 1. Write path: each save passes through the compile-time decorator chain
            //    (PrefixCompletedTodoRepository -> TracingTodoRepository -> AuditingTodoRepository -> InMemoryTodoRepository).
            commands.dispatch(AddTodo(id = "1", title = "Ship modulith"))
            commands.dispatch(CompleteTodo(id = "1"))

            // 2. Read path: PrefixCompletedTodoRepository (features module) adds "[DONE] " on completed todos.
            val items = queries.ask(ListTodos)
            check(items == listOf(TodoItem(id = "1", title = "[DONE] Ship modulith", completed = true))) {
                "Unexpected modulith todo list: $items"
            }

            // 3. Cross-cutting side effects recorded by the audit and telemetry modules.
            check(auditLog.entries().any { it.contains("[DONE] Ship modulith") }) {
                "Missing completed audit entry in: ${auditLog.entries()}"
            }
            check(spans.spans().any { it.startsWith("todo-modulith:TodoRepository.save(1)") }) {
                "Missing save span in: ${spans.spans()}"
            }

            println("Todos: $items")
            println("Audit: ${auditLog.entries()}")
            println("Spans: ${spans.spans()}")
        }
}
