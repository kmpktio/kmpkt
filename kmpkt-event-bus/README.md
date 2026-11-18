# kmpkt-event-bus

Fan-out event publishing facade over KmpKt Messenger. Events are dispatched onto a bus with `HandlerPolicy.MULTIPLE`, allowing zero or more handlers per event type.

## Building Blocks

| Symbol | Purpose |
| --- | --- |
| `Event` | Marker interface for domain or application facts |
| `EventHandler` | Handler invoked when a matching `Event` is published |
| `EventBus` | Facade wrapping `MessageBus` to publish events |
| `EventComponent` | DI component that configures the `"event"` bus and registers `EventBus` |
| `eventBus` | `MessengerOptions` DSL helper for configuring the `"event"` bus |

## Installation

Add the dependency in `build.gradle.kts` (with `id("io.kmpkt.di")` applied, `EventComponent` is discovered automatically):

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-event-bus:0.1.0")
}
```

## DI Setup

Discover your event handlers and customize the `"event"` bus in `src/di/kotlin`:

```kotlin
// src/di/kotlin/AppConfiguration.kt
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.event.EventHandler
import io.kmpkt.event.di.eventBus
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.logging.LoggingMiddleware

class AppConfiguration : Configuration<MyApp> {
    override fun ContainerBuilder.configure() {
        discover("com.example.app") {
            services<EventHandler<*>>()
        }

        messenger {
            eventBus {
                middleware<LoggingMiddleware>()
            }
        }
    }
}
```

## Usage

Define events and handlers in `src/main/kotlin`, then inject and invoke `EventBus`:

```kotlin
// src/main/kotlin/ItemChecked.kt
import io.kmpkt.event.Event
import io.kmpkt.event.EventBus
import io.kmpkt.event.EventHandler

data class ItemChecked(val name: String) : Event

class NotifyOnItemChecked : EventHandler<ItemChecked> {
    override suspend fun handle(event: ItemChecked) {
        // React to the event
    }
}

class CheckoutService(private val eventBus: EventBus) {
    suspend fun complete(item: String) {
        eventBus.publish(ItemChecked(item))
    }
}
```

## Extending

### Async event routing

Route specific event types to a queued transport inside `eventBus` in `src/di/kotlin`:

```kotlin
messenger {
    val async = transport<InMemoryTransport>("async")
    eventBus {
        route<ItemChecked>(async)
    }
}
```

### Handler ordering

Tag handlers with `MessageHandlerTag` targeting `"event"` and set a priority (highest runs first):

```kotlin
service<AuditItemCheckedHandler> {
    tag(MessageHandlerTag(bus = "event", priority = 100))
}
```

### Subtype listening

Because `EventHandler` is contravariant, a handler registered for a base `Event` interface receives subtypes dispatched as that base:

```kotlin
class AllEventsAuditHandler : EventHandler<Event> {
    override suspend fun handle(event: Event) {
        println("Observed ${event::class.simpleName}")
    }
}
```
