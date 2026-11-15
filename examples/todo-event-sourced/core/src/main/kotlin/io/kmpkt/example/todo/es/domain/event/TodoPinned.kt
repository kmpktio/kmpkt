package io.kmpkt.example.todo.es.domain.event

import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.example.todo.es.domain.aggregate.TodoItem
import io.kmpkt.serialization.kotlinx.AggregateUuidSerializer
import io.kmpkt.serialization.kotlinx.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
@SerialName("TodoPinned")
data class TodoPinned(
    @Serializable(with = AggregateUuidSerializer::class)
    override val aggregate: AggregateId<TodoItem>,
    override val version: Long,
    @Serializable(with = InstantSerializer::class)
    override val occurredAt: Instant,
) : TodoEvent
