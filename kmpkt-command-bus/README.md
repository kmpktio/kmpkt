# kmpkt-command-bus

Typed command-dispatching facade over KmpKt Messenger. Each command type maps to a single handler and inherits the underlying bus's middleware, routing, and retry pipeline.

## Building Blocks

| Symbol | Purpose |
| --- | --- |
| `Command` | Marker interface for write-intent messages |
| `CommandHandler` | Single-responsibility handler for a `Command` subtype |
| `CommandBus` | Facade wrapping `MessageBus` to dispatch commands |
| `CommandComponent` | DI component that configures the `"command"` bus and registers `CommandBus` |
| `commandBus` | `MessengerOptions` DSL helper for configuring the `"command"` bus |

## Installation

Add the dependency in `build.gradle.kts` (with `id("io.kmpkt.di")` applied, `CommandComponent` is discovered automatically):

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-command-bus:0.1.0")
}
```

## DI Setup

Discover your handlers and customize the `"command"` bus in `src/di/kotlin`:

```kotlin
// src/di/kotlin/AppConfiguration.kt
import io.kmpkt.command.CommandHandler
import io.kmpkt.command.di.commandBus
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.logging.LoggingMiddleware

class AppConfiguration : Configuration<MyApp> {
    override fun ContainerBuilder.configure() {
        discover("com.example.app") {
            services<CommandHandler<*>>()
        }

        messenger {
            commandBus {
                middleware<LoggingMiddleware>()
            }
        }
    }
}
```

## Usage

Define commands and handlers in `src/main/kotlin`, then inject and invoke `CommandBus`:

```kotlin
// src/main/kotlin/SortList.kt
import io.kmpkt.command.Command
import io.kmpkt.command.CommandBus
import io.kmpkt.command.CommandHandler

data class SortList(val listId: String) : Command

class SortListHandler : CommandHandler<SortList> {
    override suspend fun handle(command: SortList) {
        // Mutate state
    }
}

class ListController(private val commandBus: CommandBus) {
    suspend fun sort(listId: String) {
        commandBus.dispatch(SortList(listId))
    }
}
```

## Extending

### Custom middleware

Implement `Middleware` and register it inside the `commandBus` block in `src/di/kotlin`:

```kotlin
class AuditMiddleware : Middleware {
    override suspend fun handle(envelope: Envelope, next: MiddlewareChain): Envelope {
        println("Dispatching ${envelope.message::class.simpleName}")
        return next(envelope)
    }
}
```

### Async routing

Route specific command types to a queued transport inside `commandBus`:

```kotlin
messenger {
    val async = transport<InMemoryTransport>("async")
    commandBus {
        route<SortList>(async)
    }
}
```

### Handler targeting and priority

Tag a handler with `MessageHandlerTag` in `src/di/kotlin` to override discovery or priority ordering:

```kotlin
service<SortListHandler> {
    tag(MessageHandlerTag(bus = "command", priority = 10))
}
```
