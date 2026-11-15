package io.kmpkt.example.todo.es.outbound.serialization

import io.kmpkt.event.store.serialization.EventSerializer
import io.kmpkt.example.todo.es.domain.event.TodoEvent
import io.kmpkt.serialization.kotlinx.KotlinxEventSerializer

fun todoEventSerializer(): EventSerializer = KotlinxEventSerializer(TodoEvent.serializer())
