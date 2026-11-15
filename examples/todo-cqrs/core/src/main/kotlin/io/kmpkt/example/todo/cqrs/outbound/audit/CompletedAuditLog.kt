package io.kmpkt.example.todo.cqrs.outbound.audit

import io.kmpkt.event.EventHandler
import io.kmpkt.example.todo.cqrs.domain.event.TodoCompleted

class CompletedAuditLog : EventHandler<TodoCompleted> {
    val entries: MutableList<String> = mutableListOf()

    override suspend fun handle(event: TodoCompleted) {
        entries += "completed todo #${event.id}: ${event.title}"
    }
}
