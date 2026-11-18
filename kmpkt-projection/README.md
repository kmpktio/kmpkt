# kmpkt-projection

Read-model projections built from `EventStore` events, decoupled from storage and transport. Projectors read from a `ProjectionView` and return `ProjectionChange` values applied by a `ProjectionStore`.

## Building Blocks

| Symbol | Role |
| --- | --- |
| `Projector<in E, R>` | Produces `ProjectionChange<R>` values from an event and a read-only `ProjectionView<R>` |
| `ProjectionChange<R>`, `Put<R>`, `Remove<R>` | Sealed change hierarchy representing row upserts and removals |
| `Match<R>`, `match` | Property-based row predicate used by `Remove` and `ProjectionView` queries |
| `ProjectionView<R>`, `ProjectionStore<R>` | Read-only query port and read/write store port for projection rows |
| `InMemoryProjectionStore<R>` | Coroutine-safe, non-persistent `ProjectionStore<R>` keyed by a row selector |
| `ProjectionExecutor<R>` | Registry of `Projector` instances grouped by event class for a row type `R` |
| `ProjectionTransport<R>`, `ProjectionState` | Messenger `Transport` that tracks log position in `Persistence` and emits `ProjectEvent` batches |
| `ProjectEvent` | Message pairing a projection name with a `DomainEvent<*>` |
| `ProjectEventHandler<R>` | Message handler that applies `ProjectionExecutor` changes to `ProjectionStore<R>` |
| `ProjectionComponent`, `ContainerBuilder.projection` | DI component and DSL (`ProjectionOptions`) wiring projectors, transports, and handlers per projection |

## Installation

Add the dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":kmpkt-projection"))
}
```

## DI Setup

In `src/di/kotlin`, declare projections with `projection` (requires `MessengerComponent`), bind a `ProjectionStore<R>` (and `ProjectionView<R>`), and discover `Projector<*, *>` implementations:

```kotlin
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.factory
import io.kmpkt.messenger.di.messenger
import io.kmpkt.projection.InMemoryProjectionStore
import io.kmpkt.projection.ProjectionStore
import io.kmpkt.projection.ProjectionView
import io.kmpkt.projection.Projector
import io.kmpkt.projection.di.projection

class AppConfiguration : Configuration<App> {
    override fun ContainerBuilder.configure() {
        messenger()
        projection {
            projection<CounterRow>("counters")
        }

        factory { InMemoryProjectionStore<CounterRow>(CounterRow::id) }
            .apply {
                bind<ProjectionStore<CounterRow>>()
                bind<ProjectionView<CounterRow>>()
            }

        discover("com.example.app") {
            services<Projector<*, *>>()
        }
    }
}
```

## Usage

In `src/main/kotlin`, define a row type and a `Projector<E, R>` that reads from `ProjectionView<R>` and emits `ProjectionChange<R>` values:

```kotlin
import io.kmpkt.projection.ProjectionChange
import io.kmpkt.projection.ProjectionView
import io.kmpkt.projection.Projector
import io.kmpkt.projection.Put
import io.kmpkt.projection.match

data class CounterRow(val id: String, val value: Int)

class CounterIncrementedProjector : Projector<CounterIncremented, CounterRow> {
    override suspend fun project(
        event: CounterIncremented,
        view: ProjectionView<CounterRow>,
    ): List<ProjectionChange<CounterRow>> {
        val current = view.findOrNull(match(CounterRow::id to event.aggregate.toString()))
        val next = current?.copy(value = current.value + 1) ?: CounterRow(event.aggregate.toString(), 1)
        return listOf(Put(next))
    }
}
```

## Extending

**Row removal projectors:** Emit `Remove` with a `match` predicate to delete projection rows in response to domain events.

```kotlin
class CounterDeletedProjector : Projector<CounterDeleted, CounterRow> {
    override suspend fun project(
        event: CounterDeleted,
        view: ProjectionView<CounterRow>,
    ): List<ProjectionChange<CounterRow>> =
        listOf(Remove(match(CounterRow::id to event.aggregate.toString())))
}
```

**Durable projection stores:** Implement `ProjectionStore<R>` backed by a persistent database and bind it for the target row type `R`.

```kotlin
class SqlCounterProjectionStore(private val db: Database) : ProjectionStore<CounterRow> {
    override suspend fun findAll(match: Match<CounterRow>): List<CounterRow> = db.queryAll(match)
    override suspend fun findOrNull(match: Match<CounterRow>): CounterRow? = db.queryOne(match)
    override suspend fun apply(changes: List<ProjectionChange<CounterRow>>) { db.applyChanges(changes) }
    override suspend fun clear() { db.truncateCounters() }
}
```
