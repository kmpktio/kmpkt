# kmpkt-policy

Event-reaction policies that map a `DomainEvent` to follow-up `Command` instances, along with a messenger `Transport` and handler for polling the `EventStore` and dispatching decided commands onto a `CommandBus`.

## Building Blocks

| Symbol | Role |
| --- | --- |
| `Policy<in E>` | Pure reaction interface that decides follow-up commands for an event without dispatching them |
| `PolicyExecutor` | Registry of `Policy` instances grouped by event class |
| `PolicyTransport` | Messenger `Transport` that polls `EventStore` and persists progress in `Persistence` |
| `ExecutePolicyCommandsForEvent` | Message wrapping a `DomainEvent<*>` emitted by `PolicyTransport` |
| `ExecutePolicyCommandsForEventHandler` | Message handler that evaluates `PolicyExecutor` and dispatches commands to `CommandBus` |
| `PolicyComponent`, `ContainerBuilder.policy` | DI component and DSL (`PolicyOptions`) auto-discovering `Policy<*>` services and wiring transport and handler |

## Installation

Add the dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":kmpkt-policy"))
}
```

## DI Setup

In `src/di/kotlin`, configure `policy` (requires `CommandComponent` and `MessengerComponent`) and discover `Policy<*>` implementations:

```kotlin
import io.kmpkt.command.di.command
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.messenger.di.messenger
import io.kmpkt.policy.Policy
import io.kmpkt.policy.di.policy

class AppConfiguration : Configuration<App> {
    override fun ContainerBuilder.configure() {
        command()
        messenger()
        policy {
            name = "policies"
        }

        discover("com.example.app") {
            services<Policy<*>>()
        }
    }
}
```

## Usage

In `src/main/kotlin`, implement `Policy<E>` to return follow-up commands for an event, or evaluate policies directly with `PolicyExecutor`:

```kotlin
import io.kmpkt.command.Command
import io.kmpkt.policy.Policy
import io.kmpkt.policy.PolicyExecutor

class SortOnItemAdded : Policy<ItemAdded> {
    override fun decide(event: ItemAdded): List<Command> =
        listOf(SortList(event.listId))
}

fun evaluateDirectly(event: ItemAdded): List<Command> {
    val executor = PolicyExecutor().apply { register(SortOnItemAdded()) }
    return executor.commandsFor(event)
}
```

## Extending

**Sealed event hierarchies:** Implement `Policy<E>` for a sealed event base type (since `E` is contravariant) to handle multiple lifecycle events in one policy.

```kotlin
class ListLifecyclePolicy : Policy<ListEvent> {
    override fun decide(event: ListEvent): List<Command> = when (event) {
        is ListCreated -> listOf(InitializeDefaults(event.aggregate))
        is ListArchived -> listOf(NotifyOwner(event.aggregate))
    }
}
```

**Custom dispatch:** Evaluate `PolicyExecutor.commandsFor` directly when routing or batching decided commands outside `PolicyTransport` and `ExecutePolicyCommandsForEventHandler`.

```kotlin
suspend fun dispatchPolicies(executor: PolicyExecutor, bus: CommandBus, event: DomainEvent<*>) {
    executor.commandsFor(event).forEach { command ->
        bus.dispatch(command)
    }
}
```
