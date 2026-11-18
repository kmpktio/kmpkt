# kmpkt-event-store

Global append-only event log with per-stream optimistic concurrency, event serialization ports, and a replay-based `AggregateRepository` implementation.

## Building Blocks

| Symbol | Role |
| --- | --- |
| `EventStore` | Port for appending to aggregate streams, loading stream history, and tailing the global log |
| `InMemoryEventStore` | Coroutine-safe, non-persistent `EventStore` backed by in-memory collections |
| `PersistentEventStore` | `EventStore` backed by `Persistence`, `AppendLog`, and an `EventSerializer` |
| `StoredEvent` | Pairs a global log position with a `DomainEvent<*>` |
| `DefaultAggregateRepository<A>` | Repository implementation that persists recorded events and rebuilds aggregates from history |
| `EventCheckpointPredicate`, `NoOpEventCheckpointPredicate` | Identifies checkpoint events so stream loads can resume from the latest checkpoint |
| `EventSerializer`, `SerializedEvent` | Port converting between `DomainEvent<*>` and a serialized type-plus-payload pair |
| `EventPositionStamp` | Messenger stamp carrying the event's global log position |
| `EventStoreComponent`, `ContainerBuilder.eventStore` | DI component and DSL (`EventStoreOptions`) wiring `PersistentEventStore` and aggregate repositories |
| `AggregateRepositoryTag` | DI tag marking registered aggregate repository definitions |

## Installation

Add the dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":kmpkt-event-store"))
}
```

## DI Setup

In `src/di/kotlin`, configure `eventStore` alongside `persistence` and `aggregate` (`EventStoreComponent` requires `AggregateComponent` and `PersistenceComponent` and registers a `DefaultAggregateRepository<A>` for each declared aggregate):

```kotlin
import io.kmpkt.aggregate.di.aggregate
import io.kmpkt.aggregate.factory.AggregateFactory
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.service
import io.kmpkt.event.store.di.eventStore
import io.kmpkt.persistence.di.persistence

class AppConfiguration : Configuration<App> {
    override fun ContainerBuilder.configure() {
        persistence { /* ... */ }
        aggregate {
            aggregate<Counter>("counter")
        }
        eventStore {
            logName = "events"
        }

        service<CounterFactory> { bind<AggregateFactory<Counter>>() }
    }
}
```

## Usage

In `src/main/kotlin`, use `DefaultAggregateRepository` with `InMemoryEventStore` directly, or inject `AggregateRepository<A>` when wired through DI:

```kotlin
import io.kmpkt.aggregate.value.AggregateType
import io.kmpkt.event.store.InMemoryEventStore
import io.kmpkt.event.store.repository.DefaultAggregateRepository

suspend fun runExample() {
    val eventStore = InMemoryEventStore()
    val repository = DefaultAggregateRepository(
        eventStore = eventStore,
        aggregateType = AggregateType.of<Counter>(),
        factory = Counter,
    )

    val counter = Counter.create()
    counter.increment()
    repository.save(counter)

    val loaded = repository.get(counter.id)
}
```

## Extending

**Custom serialization:** Implement `EventSerializer` to convert domain events to and from `SerializedEvent`.

```kotlin
class JsonEventSerializer : EventSerializer {
    override fun serialize(event: DomainEvent<*>): SerializedEvent =
        SerializedEvent(type = event::class.qualifiedName!!, payload = encodeToJson(event))

    override fun deserialize(serialized: SerializedEvent): DomainEvent<*> =
        decodeFromJson(serialized.type, serialized.payload)
}
```

**Custom storage backend:** Implement `EventStore` directly when you need a storage engine outside `Persistence` and `AppendLog`.

```kotlin
class CustomEventStore : EventStore {
    override suspend fun append(type: AggregateType<*>, id: AggregateId<*>, expectedVersion: Long, events: List<DomainEvent<*>>) { /* ... */ }
    override suspend fun load(type: AggregateType<*>, id: AggregateId<*>): List<DomainEvent<*>> = emptyList()
    override suspend fun loadFrom(position: Long, limit: Int): List<StoredEvent> = emptyList()
    override suspend fun latestPosition(): Long = 0L
}
```

**Stream checkpoints:** Implement `EventCheckpointPredicate` to mark events that act as stream checkpoints.

```kotlin
class CompactionCheckpointPredicate : EventCheckpointPredicate {
    override fun isCheckpoint(event: DomainEvent<*>): Boolean = event is StateCompacted
}
```
