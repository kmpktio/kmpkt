package io.kmpkt.example.modulith.telemetry.outbound.telemetry

import io.kmpkt.example.modulith.todos.domain.model.TodoItem
import io.kmpkt.example.modulith.todos.domain.repository.TodoRepository

class TracingTodoRepository(
    private val delegate: TodoRepository,
    private val spans: SpanRecorder,
    private val serviceName: String,
) : TodoRepository {
    override fun save(item: TodoItem) {
        spans.record("$serviceName:TodoRepository.save(${item.id})")
        delegate.save(item)
    }

    override fun find(id: String): TodoItem? {
        spans.record("$serviceName:TodoRepository.find($id)")
        return delegate.find(id)
    }

    override fun findAll(): List<TodoItem> {
        spans.record("$serviceName:TodoRepository.findAll")
        return delegate.findAll()
    }
}
