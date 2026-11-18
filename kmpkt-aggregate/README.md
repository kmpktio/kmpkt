# kmpkt-aggregate

Domain primitives for event-sourced aggregates: typed identifiers, domain events, optimistic-locking exceptions, and the repository port. Persistence, serialization, and snapshotting live in separate modules (`kmpkt-event-store` and `kmpkt-aggregate-snapshot`).

## Building Blocks

| Symbol | Role |
| --- | --- |
| `AggregateRoot<A>` | Contract exposing an aggregate's ID, versions, and recorded events |
| `BaseAggregateRoot<A>` | Event-sourced base class supporting event recording, replay, and state restoration |
| `DomainEvent<A>` | Event subtype carrying the target aggregate ID, version, and timestamp |
| `AggregateId<A>` | Type-safe identifier interface parameterized by aggregate type |
| `AggregateUuid<A>` | Default UUID-backed identifier supporting random and deterministic name-based IDs |
| `AggregateType<A>` | Inline value class holding the persisted stream type name |
| `AggregateRepository<A>` | Port for saving and loading aggregates by ID |
| `AggregateFactory<A>` | Functional interface for rehydrating an aggregate from its event history |
| `AggregateException`, `AggregateNotFound`, `ConcurrencyConflict` | Base exception, missing-aggregate error, and recoverable optimistic-locking conflict |
| `AggregateComponent`, `ContainerBuilder.aggregate` | DI component and DSL (`AggregateOptions`) for registering aggregate types |

## Installation

Add the dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":kmpkt-aggregate"))
    // Optional test fixtures (Counter, Gauge):
    testImplementation(testFixtures(project(":kmpkt-aggregate")))
}
```

## DI Setup

In `src/di/kotlin`, register each aggregate type with `aggregate` (which configures `AggregateComponent`) and bind its `AggregateFactory`:

```kotlin
import io.kmpkt.aggregate.di.aggregate
import io.kmpkt.aggregate.factory.AggregateFactory
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.service

class AppConfiguration : Configuration<App> {
    override fun ContainerBuilder.configure() {
        aggregate {
            aggregate<Counter>("counter")
        }

        service<CounterFactory> { bind<AggregateFactory<Counter>>() }
    }
}
```

## Usage

In `src/main/kotlin`, define domain events and an aggregate extending `BaseAggregateRoot<A>` with an `AggregateFactory<A>`:

```kotlin
import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.factory.AggregateFactory
import io.kmpkt.aggregate.model.BaseAggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateUuid
import java.time.Instant

sealed interface CounterEvent : DomainEvent<Counter>

data class CounterIncremented(
    override val aggregate: AggregateId<Counter>,
    override val version: Long,
    override val occurredAt: Instant,
) : CounterEvent

class Counter private constructor(id: AggregateId<Counter>) : BaseAggregateRoot<Counter>(id) {
    var value: Int = 0
        private set

    fun increment() = recordThat(CounterIncremented(id, version + 1, Instant.now()))

    override fun updateState(event: DomainEvent<Counter>) {
        when (event) {
            is CounterIncremented -> value += 1
        }
    }

    companion object : AggregateFactory<Counter> {
        fun create(id: AggregateId<Counter> = AggregateUuid.generate()): Counter = Counter(id)

        override fun fromHistory(history: List<DomainEvent<Counter>>): Counter =
            Counter(history.first().aggregate).apply { history.forEach(::replay) }
    }
}
```

## Extending

**Custom aggregate root:** Implement `AggregateRoot<A>` directly when managing state or uncommitted events outside `BaseAggregateRoot<A>`.

```kotlin
class CustomOrder(override val id: AggregateId<CustomOrder>) : AggregateRoot<CustomOrder> {
    override var version: Long = 0L
        private set
    override var persistedVersion: Long = 0L
        private set
    private val pending = mutableListOf<DomainEvent<CustomOrder>>()

    override fun pullRecordedEvents(): List<DomainEvent<CustomOrder>> =
        pending.toList().also { pending.clear() }
}
```

**Custom aggregate ID:** Implement `AggregateId<A>` to preserve an external identifier rather than hashing with `AggregateUuid.fromName`.

```kotlin
@JvmInline
value class OrderNumber(val value: String) : AggregateId<Order>
```

**Custom repository:** Implement `AggregateRepository<A>` when not using `DefaultAggregateRepository` or `SnapshotAggregateRepository`.

```kotlin
class CustomOrderRepository : AggregateRepository<Order> {
    override suspend fun save(aggregate: Order) { /* persist events */ }
    override suspend fun findOrNull(id: AggregateId<Order>): Order? = null
    override suspend fun get(id: AggregateId<Order>): Order =
        findOrNull(id) ?: throw AggregateNotFound(id)
}
```

**DI registration hooks:** Use `AggregateRegistration.extend` to attach per-aggregate DI configuration from downstream modules.

```kotlin
fun AggregateRegistration.withCustomService() = extend {
    factory { CustomOrderRepository() }.bind<AggregateRepository<Order>>()
}
```
