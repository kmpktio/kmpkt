# kmpkt-aggregate-snapshot

Optional in-stream snapshotting for event-sourced aggregates. Records `SnapshotEvent` checkpoints into the aggregate's event stream so `EventStore.load` replays only from the latest snapshot forward.

## Building Blocks

| Symbol | Role |
| --- | --- |
| `Snapshotable<A>` | Contract declaring snapshot creation on an aggregate root |
| `SnapshotableAggregateRoot<A>` | `BaseAggregateRoot<A>` subclass that restores from `SnapshotEvent` checkpoints and replays later events |
| `SnapshotEvent<A>` | Marker interface extending `DomainEvent<A>` for checkpoint events |
| `SnapshotEventCheckpointPredicate` | Checkpoint predicate matching `SnapshotEvent` instances |
| `SnapshotAggregateRepository<A>` | Repository decorator that takes a snapshot before saving when the strategy allows |
| `SnapshotStrategy` | Functional interface deciding when an aggregate save should trigger a snapshot |
| `VersionIntervalSnapshotStrategy` | Built-in strategy that snapshots whenever the version crosses a configured interval (default `50`) |
| `AggregateSnapshotComponent`, `ContainerBuilder.aggregateSnapshot` | DI component and DSL (`AggregateSnapshotOptions`) wiring default or per-aggregate snapshot strategies |

## Installation

Add the dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":kmpkt-aggregate-snapshot"))
}
```

## DI Setup

In `src/di/kotlin`, enable snapshotting per aggregate with `withSnapshots` (requires `AggregateComponent` and `EventStoreComponent`), or set a default strategy via `aggregateSnapshot`:

```kotlin
import io.kmpkt.aggregate.di.aggregate
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.snapshot.di.interval
import io.kmpkt.snapshot.di.strategy
import io.kmpkt.snapshot.di.withSnapshots
import io.kmpkt.snapshot.strategy.VersionIntervalSnapshotStrategy

class AppConfiguration : Configuration<App> {
    override fun ContainerBuilder.configure() {
        aggregate {
            aggregate<Counter>("counter") withSnapshots strategy<VersionIntervalSnapshotStrategy> {
                interval = 50
            }
        }
    }
}
```

## Usage

In `src/main/kotlin`, define a `SnapshotEvent<A>` and extend `SnapshotableAggregateRoot<A>`:

```kotlin
import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.factory.AggregateFactory
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.snapshot.event.SnapshotEvent
import io.kmpkt.snapshot.model.SnapshotableAggregateRoot
import java.time.Instant

data class CounterSnapshotted(
    override val aggregate: AggregateId<Counter>,
    override val version: Long,
    override val occurredAt: Instant,
    val value: Int,
) : SnapshotEvent<Counter>

class Counter private constructor(id: AggregateId<Counter>) : SnapshotableAggregateRoot<Counter>(id) {
    var value: Int = 0
        private set

    override fun snapshot() {
        recordThat(CounterSnapshotted(id, version + 1, Instant.now(), value))
    }

    override fun updateState(event: DomainEvent<Counter>) {
        when (event) {
            is CounterSnapshotted -> value = event.value
            is CounterIncremented -> value += 1
        }
    }

    companion object : AggregateFactory<Counter> {
        override fun fromHistory(history: List<DomainEvent<Counter>>): Counter =
            Counter(history.first().aggregate).apply { replayAll(history) }
    }
}
```

## Extending

**Custom snapshot strategy:** Implement `SnapshotStrategy` and register it as the default strategy in `aggregateSnapshot` or per aggregate with `withSnapshots`.

```kotlin
class EveryTenEventsStrategy : SnapshotStrategy {
    override fun shouldSnapshot(previousVersion: Long, newVersion: Long): Boolean =
        newVersion / 10 > previousVersion / 10
}

fun ContainerBuilder.configureSnapshots() = aggregateSnapshot {
    strategy<EveryTenEventsStrategy>()
}
```

**Custom checkpoint detection:** Implement `EventCheckpointPredicate` from `kmpkt-event-store` if you need checkpoint rules beyond `SnapshotEventCheckpointPredicate`.

```kotlin
class CustomCheckpointPredicate : EventCheckpointPredicate {
    override fun isCheckpoint(event: DomainEvent<*>): Boolean =
        event is SnapshotEvent<*> || event is LegacyCheckpointEvent
}
```
