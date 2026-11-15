# Todo List — CQRS + Event Sourcing

The same Todo domain as [`todo-cqrs`](../todo-cqrs), rebuilt on event-sourced aggregates,
read-model projections, and reactive policies.

## Domain

A todo list where every task is an event-sourced `TodoItem` aggregate. Adding a task whose title
ends with `!` automatically pins it as urgent, while read queries consume a projected `TodoRow`
view.

## What this example shows

**Event-sourced state.** `TodoItem` extends `BaseAggregateRoot` and records `TodoAdded`,
`TodoCompleted`, and `TodoPinned` events instead of mutating rows directly. Events are
serialized with `KotlinxEventSerializer`.

**Read models via projectors.** `WhenTodoAddedProjectRow`, `WhenTodoCompletedProjectRow`, and
`WhenTodoPinnedProjectRow` turn domain events into `TodoRow` entries stored in
`InMemoryProjectionStore`. Queries read from `ProjectionView<TodoRow>` without touching the
aggregate.

**Reactive policies.** When a todo title ends with `!`, `PinUrgentTodoPolicy` reacts to
`TodoAdded` and dispatches a follow-up `PinTodo` command.

**Explicit bus wiring & demo entry point.** [TodoEventSourcedConfiguration](core/src/di/kotlin/io/kmpkt/example/todo/es/di/TodoEventSourcedConfiguration.kt)
passes the `"command"` bus to [`DemoInteractionRunner`](core/src/main/kotlin/io/kmpkt/example/todo/es/inbound/runner/DemoInteractionRunner.kt)
in code — no `@Named` annotation needed. `DemoInteractionRunner` acts as the simulated client
interaction and transport driver for the runnable example.

## Project layout

| Module | Contents |
| --- | --- |
| `core` | Aggregate, events, projectors, policy, [`DemoInteractionRunner`](core/src/main/kotlin/io/kmpkt/example/todo/es/inbound/runner/DemoInteractionRunner.kt), and configuration |
| `app` | [Main.kt](app/src/main/kotlin/io/kmpkt/example/todo/es/Main.kt) and [TodoAppTest.kt](app/src/test/kotlin/io/kmpkt/example/todo/es/TodoAppTest.kt) |

## Run it

```bash
./gradlew :examples:todo-event-sourced:app:test
```

Looking for the simpler CRUD-style version without event sourcing?
See [Todo List — CQRS without Event Sourcing](../todo-cqrs).
