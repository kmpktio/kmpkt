package io.kmpkt.example.todo.es.domain.query

import io.kmpkt.example.todo.es.application.projection.TodoRow
import io.kmpkt.query.Query

data object ListTodos : Query<List<TodoRow>>
