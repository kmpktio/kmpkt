# Todo List — CQRS without Event Sourcing

A minimal Todo application built with typed command, query, and event buses, wired at compile
time without annotations or event sourcing.

## Domain

A small personal todo list: tasks can be added (`AddTodo`), completed (`CompleteTodo`), and
listed (`ListTodos`), emitting `TodoCompleted` when an item is checked off.

## What this example shows

- **Separate read and write paths.** `AddTodo` and `CompleteTodo` go through `CommandBus`,
  `ListTodos` goes through `QueryBus`, and `TodoCompleted` is published on `EventBus`.
- **Automatic handler discovery.** Instead of registering every handler by hand,
  [TodoCqrsConfiguration](core/src/di/kotlin/io/kmpkt/example/todo/cqrs/di/TodoCqrsConfiguration.kt)
  scans the package for `CommandHandler`, `QueryHandler`, and `EventHandler` implementations.
- **Interface binding.** `InMemoryTodoRepository` is bound to `TodoRepository` in one line.
- **Annotation-free startup & demo entry point.** `TodoApp` is a plain `KmpKtApplication` object
  started from `main` via `KmpKt.run<TodoApp>(args)`, invoking
  [`DemoInteractionRunner`](core/src/main/kotlin/io/kmpkt/example/todo/cqrs/inbound/runner/DemoInteractionRunner.kt)
  to simulate a client interacting with the app.

## Project layout

| Module | Contents |
| --- | --- |
| `core` | Domain types, handlers, [`DemoInteractionRunner`](core/src/main/kotlin/io/kmpkt/example/todo/cqrs/inbound/runner/DemoInteractionRunner.kt), and `TodoCqrsConfiguration` |
| `app` | [Main.kt](app/src/main/kotlin/io/kmpkt/example/todo/cqrs/Main.kt) and [TodoAppTest.kt](app/src/test/kotlin/io/kmpkt/example/todo/cqrs/TodoAppTest.kt) |

## Run it

```bash
./gradlew :examples:todo-cqrs:app:test
```

Want to see the same domain with event sourcing, projections, and policies?
Check out [Todo List — CQRS + Event Sourcing](../todo-event-sourced).
