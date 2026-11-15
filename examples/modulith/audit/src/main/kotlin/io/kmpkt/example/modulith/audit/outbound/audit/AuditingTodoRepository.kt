package io.kmpkt.example.modulith.audit.outbound.audit

import io.kmpkt.example.modulith.todos.domain.model.TodoItem
import io.kmpkt.example.modulith.todos.domain.repository.TodoRepository

class AuditingTodoRepository(
    private val delegate: TodoRepository,
    private val auditLog: AuditLog,
) : TodoRepository by delegate {
    override fun save(item: TodoItem) {
        auditLog.record("save:${item.id}:${item.title}:completed=${item.completed}")
        delegate.save(item)
    }
}
