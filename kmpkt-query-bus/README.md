# kmpkt-query-bus

Typed request/response facade over KmpKt Messenger. Each query type maps to a single handler and returns its typed result, unwrapping single-cause `HandlerFailedException` failures automatically.

## Building Blocks

| Symbol | Purpose |
| --- | --- |
| `Query` | Marker interface for read requests with a typed result |
| `QueryHandler` | Handler that processes a `Query` and returns its result |
| `QueryBus` | Facade wrapping `MessageBus` to execute queries and unwrap single failures |
| `QueryComponent` | DI component that configures the `"query"` bus and registers `QueryBus` |
| `queryBus` | `MessengerOptions` DSL helper for configuring the `"query"` bus |

## Installation

Add the dependency in `build.gradle.kts` (with `id("io.kmpkt.di")` applied, `QueryComponent` is discovered automatically):

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-query-bus:0.1.0")
}
```

## DI Setup

Discover your query handlers and customize the `"query"` bus in `src/di/kotlin`:

```kotlin
// src/di/kotlin/AppConfiguration.kt
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.logging.LoggingMiddleware
import io.kmpkt.query.QueryHandler
import io.kmpkt.query.di.queryBus

class AppConfiguration : Configuration<MyApp> {
    override fun ContainerBuilder.configure() {
        discover("com.example.app") {
            services<QueryHandler<*, *>>()
        }

        messenger {
            queryBus {
                middleware<LoggingMiddleware>()
            }
        }
    }
}
```

## Usage

Define queries and handlers in `src/main/kotlin`, then inject and invoke `QueryBus`:

```kotlin
// src/main/kotlin/FindTodo.kt
import io.kmpkt.query.Query
import io.kmpkt.query.QueryBus
import io.kmpkt.query.QueryHandler

data class FindTodo(val id: String) : Query<String?>

class FindTodoHandler : QueryHandler<FindTodo, String?> {
    override suspend fun handle(query: FindTodo): String? = "Buy milk"
}

class TodoEndpoint(private val queryBus: QueryBus) {
    suspend fun load(id: String): String? = queryBus.ask(FindTodo(id))
}
```

## Extending

### Custom middleware

Implement `Middleware` for caching, authorization, or metrics, and register it inside `queryBus`:

```kotlin
class TimingMiddleware : Middleware {
    override suspend fun handle(envelope: Envelope, next: MiddlewareChain): Envelope {
        val start = System.nanoTime()
        return next(envelope).also {
            println("Query took ${System.nanoTime() - start} ns")
        }
    }
}
```

### Handler targeting and priority

Tag a handler with `MessageHandlerTag` in `src/di/kotlin` when explicit bus assignment or ordering is needed:

```kotlin
service<FindTodoHandler> {
    tag(MessageHandlerTag(bus = "query", priority = 10))
}
```

### Decorating `QueryBus`

Wrap `QueryBus` or `MessageBus` in DI using `decorates<MessageBus>()` when cross-cutting query behavior needs bus-level wrapping:

```kotlin
service<MetricsMessageBus> {
    decorates<MessageBus>(priority = 10)
}
```
