package io.kmpkt.example.todo.es.outbound.persistence

import io.kmpkt.example.todo.es.application.projection.TodoRow
import io.kmpkt.projection.InMemoryProjectionStore

fun todoRowStore(): InMemoryProjectionStore<TodoRow> = InMemoryProjectionStore(TodoRow::id)
