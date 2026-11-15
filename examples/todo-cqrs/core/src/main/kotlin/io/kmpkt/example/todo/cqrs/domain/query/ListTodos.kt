package io.kmpkt.example.todo.cqrs.domain.query

import io.kmpkt.example.todo.cqrs.domain.model.TodoItem
import io.kmpkt.query.Query

data object ListTodos : Query<List<TodoItem>>
