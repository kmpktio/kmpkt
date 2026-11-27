# kmpkt-worker

Coroutine-based polling loop (`Worker`) with configurable idle backoff, retry strategies, and optional distributed locking (`WorkLock`) for KmpKt background tasks. Depends only on `kotlinx-coroutines-core` and `slf4j-api`.

## Building Blocks

| Symbol | Description |
| --- | --- |
| `Work` | Functional interface for a single polling pass returning the processed item count |
| `Worker` | Sealed interface representing a named polling loop |
| `ManagedWorker` | Lifecycle-managed worker with `start()`, `stop()`, `isRunning`, and `onGiveUp` handling |
| `WorkerRunner` | `ApplicationRunner` that starts registered `ManagedWorker` instances on boot and stops them on shutdown |
| `createManagedWorker` | Factory helper creating a `ManagedWorker` with a per-worker logger |
| `UnmanagedWorker` | Foreground worker running in the caller's coroutine and rethrowing exhausted failures |
| `RetryStrategy` | Backoff strategy with `none`, `fixed`, `backOff`, and `forever` factories |
| `WorkLock` | Mutual-exclusion lock interface for single-active-worker execution |
| `LockedWork` | `Work` decorator that runs a delegate only when the `WorkLock` is acquired |
| `WorkerComponent` | DI component that discovers `Work` services and registers a `ManagedWorker` for each |
| `WorkerOptions` | Configuration options (`autoStart`) for `WorkerComponent` |
| `ContainerBuilder.workers` | DSL extension function for configuring `WorkerComponent` |
| `WorkTag`, `ManagedWorkerTag` | DI tags for discovered `Work` and generated `ManagedWorker` services |
| `RegisterWorkersPass` | `BuilderPass` that wires a `ManagedWorker` for each tagged `Work` service |

## Installation

Add the dependency in `build.gradle.kts` (with `id("io.kmpkt.di")` applied, `WorkerComponent` is discovered automatically):

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-worker:0.1.0")
}
```

## DI Setup

Discover `Work` implementations in `src/di/kotlin`; `WorkerComponent` automatically tags them with `WorkTag` and registers a `ManagedWorker` for each:

```kotlin
// src/di/kotlin/AppConfiguration.kt
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.worker.Work

class AppConfiguration : Configuration<MyApp> {
    override fun ContainerBuilder.configure() {
        discover("com.example.app") {
            services<Work>()
        }
    }
}
```

## Usage

Implement `Work` in `src/main/kotlin` and inject `List<ManagedWorker>` (or run `ManagedWorker` / `UnmanagedWorker` standalone):

```kotlin
// src/main/kotlin/OutboxWork.kt
import io.kmpkt.worker.ManagedWorker
import io.kmpkt.worker.Work

class OutboxWork : Work {
    override suspend fun run(): Int {
        // Poll and process items; return the count handled
        return 0
    }
}

class WorkerLifecycle(private val workers: List<ManagedWorker>) {
    fun startAll() = workers.forEach { it.start() }
}
```

For standalone usage without DI:

```kotlin
val managed = createManagedWorker(
    name = "outbox",
    work = OutboxWork(),
    pollInterval = 1.seconds,
    retry = RetryStrategy.backOff(times = 5),
)
managed.start()

// Or foreground execution (throws on exhausted retries):
UnmanagedWorker("outbox", OutboxWork(), logger).run()
```

## Extending

### Custom `RetryStrategy`

Implement `RetryStrategy` to customize backoff or stop conditions based on the attempt count, returning `null` to stop retrying:

```kotlin
class CappedRetryStrategy(private val maxAttempts: Int) : RetryStrategy {
    override fun delayBefore(attempt: Int): Duration? =
        if (attempt <= maxAttempts) 5.seconds else null
}
```

### Distributed locking

Implement `WorkLock` (for example with database advisory locks or Redis) and wrap `Work` in `LockedWork` for single-active-worker execution:

```kotlin
class RedisWorkLock : WorkLock {
    override suspend fun <R> underLock(name: String, block: suspend () -> R): R? =
        if (tryAcquire(name)) try { block() } finally { release(name) } else null
}
```

### Worker tags

Tag `Work` implementations with `WorkTag` in `src/di/kotlin` to control registration or inject all generated workers via `ManagedWorkerTag`:

```kotlin
service<OutboxWork> {
    tag(WorkTag)
}
```
