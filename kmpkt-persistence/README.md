# kmpkt-persistence

Domain-agnostic storage ports for KmpKt with coroutine-context-propagated transactions, named locks, and an in-memory reference implementation.

## Building Blocks

| Symbol | Role |
| --- | --- |
| `Persistence` | Entry point providing named stores, atomic transactions, named locks, and setup. |
| `KeyValueStore` | String key-value storage port. |
| `AppendLog` | Partitioned, optimistically versioned append-only log using `NewLogEntry`, `LogEntry`, and `LogVersionConflictException`. |
| `WorkQueue` | Delayed work queue supporting claiming, acknowledgements, requeuing, and dead-lettering via `QueuedItem`. |
| `createKeyValueStore` / `createAppendLog` / `createWorkQueue` | Top-level factories delegating to `Persistence`. |
| `InMemoryPersistence` | Coroutine-safe in-memory `Persistence` backed by a `Mutex` with snapshot rollback. |
| `PersistenceComponent` & `PersistenceOptions` | DI component binding `InMemoryPersistence` and registering named stores via `persistence`. |

## Installation

Add the dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-persistence:0.1.0")
}
```

## DI Setup

Register `PersistenceComponent` and declare named stores in `src/di/kotlin`:

```kotlin
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.persistence.di.PersistenceComponent
import io.kmpkt.persistence.di.persistence

class AppConfiguration : Configuration<App> {
    override fun ContainerBuilder.configure() {
        use<PersistenceComponent>()

        persistence {
            keyValue("cursors")
            appendLog("events")
            workQueue("outbox")
        }
    }
}
```

## Usage

Use `InMemoryPersistence` (or an injected `Persistence` instance) in `src/main/kotlin`:

```kotlin
import io.kmpkt.persistence.InMemoryPersistence
import io.kmpkt.persistence.NewLogEntry
import java.time.Instant

val persistence = InMemoryPersistence()
val log = persistence.appendLog("events")
val kv = persistence.keyValueStore("cursors")

persistence.inTransaction {
    log.append(
        streamType = "Order",
        streamId = "order-1",
        expectedVersion = 0L,
        entries = listOf(
            NewLogEntry(
                id = "evt-1",
                type = "OrderCreated",
                payload = """{"total":100}""",
                occurredAt = Instant.now(),
            ),
        ),
    )
    kv.put("projection-a", "1")
}
```

## Extending

Implement `Persistence` along with `KeyValueStore`, `AppendLog`, and `WorkQueue` to back storage with a custom engine:

```kotlin
class CustomPersistence : Persistence {
    override fun keyValueStore(name: String): KeyValueStore = CustomKeyValueStore(name)
    override fun appendLog(name: String): AppendLog = CustomAppendLog(name)
    override fun workQueue(name: String): WorkQueue = CustomWorkQueue(name)
    override suspend fun <R> inTransaction(block: suspend () -> R): R = TODO()
    override suspend fun <R> underLock(name: String, block: suspend () -> R): R? = TODO()
}
```

Propagate active transactions through `CoroutineContext` so nested `inTransaction` calls share one atomic unit, and return `null` from `underLock` without blocking if the lock is already held:

```kotlin
override suspend fun <R> underLock(name: String, block: suspend () -> R): R? {
    if (!tryAcquireLock(name)) return null
    return try {
        block()
    } finally {
        releaseLock(name)
    }
}
```

Enforce optimistic concurrency in `AppendLog.append` by throwing `LogVersionConflictException` when the stream version does not match `expectedVersion`:

```kotlin
if (currentVersion != expectedVersion) {
    throw LogVersionConflictException(streamType, streamId, expectedVersion, currentVersion)
}
```

Override `list`, `find`, and `remove` on `WorkQueue` if consumers need to inspect or manage pending items directly:

```kotlin
override fun list(): Flow<QueuedItem> = itemsFlow()
override suspend fun find(id: String): QueuedItem? = items[id]
override suspend fun remove(id: String): Boolean = items.remove(id) != null
```
