# kmpkt-query-bus

`Query`, its handler, and the bus that routes a read request to one handler and returns its
result. Kept separate from [`kmpkt-command-bus`](../kmpkt-command-bus) so read paths and
projections do not need to pull in command handling.

## Scope

- `Query<R>`: the marker for a read request returning `R`. It is a
  [`kmpkt-messenger`](../kmpkt-messenger) `Message`, which is what lets it travel over a bus.
- `QueryHandler<Q, R>`: answers one kind of query. Contravariant in `Q`, so a handler written
  against a supertype also serves its subtypes. It is the `MessageHandler<Q, R>` for its query.
- `QueryBus`: asks a query on a `MessageBus`, so it gets that bus's middleware and logging without
  exposing `Envelope` to callers, and unwraps single `HandlerFailedException` causes.

## Usage

```kotlin
data class FindTodo(val id: String) : Query<Todo?>

class FindTodoHandler(...) : QueryHandler<FindTodo, Todo?> {
    override suspend fun handle(query: FindTodo): Todo? { ... }
}

// Registered via QueryComponent + discover(...) in Configuration
val todo = QueryBus(messageBus).ask(FindTodo(id))
```
