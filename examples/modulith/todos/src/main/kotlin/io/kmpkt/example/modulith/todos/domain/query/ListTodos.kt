package io.kmpkt.example.modulith.todos.domain.query

import io.kmpkt.example.modulith.todos.domain.model.TodoItem
import io.kmpkt.query.Query

data object ListTodos : Query<List<TodoItem>>
