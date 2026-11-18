# kmpkt-messenger-clikt

Clikt CLI commands for KmpKt Messenger to consume queued transports, provision `SetupableTransport` infrastructure, and inspect, retry, or remove failed messages on `ListableTransport` failure queues.

## Building Blocks

| Symbol | Purpose |
| --- | --- |
| `ConsumeCommand` | Clikt command (`messenger:consume`) consuming prioritized transports with limit, sleep, and lock options |
| `SetupTransportsCommand` | Clikt command (`messenger:setup-transports`) provisioning selected or all `SetupableTransport` instances |
| `ShowFailedCommand` | Clikt command (`messenger:failed:show`) listing or inspecting failed messages on a `ListableTransport` |
| `RetryFailedCommand` | Clikt command (`messenger:failed:retry`) re-dispatching failed messages by ID or `--all` and removing on success |
| `RemoveFailedCommand` | Clikt command (`messenger:failed:remove`) removing failed messages by ID or `--all` from a failure transport |
| `MessengerCliktComponent` | DI component registering all five commands for `CliktComponent` |

## Installation

Add the dependency in `build.gradle.kts` (with `id("io.kmpkt.di")` applied, `MessengerCliktComponent` is discovered automatically):

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-messenger-clikt:0.1.0")
}
```

## DI Setup

`MessengerCliktComponent` requires `MessengerComponent` and `CliktComponent` on the classpath and registers all five commands automatically in `src/di/kotlin`:

```kotlin
// src/di/kotlin/AppConfiguration.kt
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.transport.InMemoryTransport

class AppConfiguration : Configuration<MyApp> {
    override fun ContainerBuilder.configure() {
        messenger {
            transport<InMemoryTransport>("high")
            transport<InMemoryTransport>("low")
        }
    }
}
```

## Usage

Run the registered Clikt commands from your CLI application entrypoint:

```sh
app messenger:setup-transports
app messenger:consume high low --limit 100 --sleep 250 --lock consumer-lock
app messenger:failed:show --max 20
app messenger:failed:retry --all
app messenger:failed:remove <id>
```

## Extending

### Custom transports in CLI commands

Implement `SetupableTransport` for transports needing provisioning, and `ListableTransport` for failure queues inspected by `messenger:failed:*`:

```kotlin
class CustomQueueTransport : SetupableTransport, ListableTransport {
    override suspend fun setup() { /* create tables or topics */ }
    override suspend fun list(limit: Int): List<QueuedEnvelope> = emptyList()
    // ... Transport methods
}
```

### Leader-locked consumption

Bind a `WorkLock` service (such as `PersistenceWorkLock` from `kmpkt-messenger-persistence`) to enable the `--lock` option on `messenger:consume`:

```kotlin
service<RedisWorkLock> {
    bind<WorkLock>()
}
```

### Additional CLI commands

Register custom `CliktCommand` services that inject `MessengerCatalog` alongside `MessengerCliktComponent`:

```kotlin
class ListTransportsCommand(private val catalog: MessengerCatalog) : CliktCommand(name = "messenger:list") {
    override fun run() = catalog.transports.keys.forEach(::echo)
}
```
