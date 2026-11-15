package io.kmpkt.example.modulith.audit.di

import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.example.modulith.audit.outbound.audit.AuditLog
import io.kmpkt.example.modulith.audit.outbound.audit.AuditingTodoRepository
import io.kmpkt.example.modulith.todos.domain.repository.TodoRepository

class AuditComponent : SimpleComponent {
    override fun ContainerBuilder.register() {
        service<AuditLog>()
        service<AuditingTodoRepository> {
            decorates<TodoRepository>(priority = 10)
        }
    }
}
