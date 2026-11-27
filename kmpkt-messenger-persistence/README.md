# kmpkt-messenger-persistence

Durable transport, transactional middleware, and distributed locking adapters connecting KmpKt Messenger to KmpKt Persistence.

## Building Blocks

| Symbol | Description |
| --- | --- |
| `PersistentTransport` | `SetupableTransport` and `ListableTransport` backed by a `WorkQueue` |
| `TransactionMiddleware` | Middleware wrapping downstream execution in `Persistence.inTransaction` |
| `EnvelopeSerializer` | Serialization port decoupling `PersistentTransport` from concrete formats |
| `PersistentQueueItemStamp` | Stamp tracking the `WorkQueue` item ID for `ack` and `reject` |
| `PersistenceWorkLock` | `WorkLock` implementation delegating to `Persistence.underLock` |
| `MessengerPersistenceComponent` | DI component registering `TransactionMiddleware`, `PersistenceWorkLock`, and transport wiring |
| `WirePersistentTransportsPass` | `BuilderPass` provisioning a dedicated `WorkQueue` for each `PersistentTransport` |

## Installation

Add the dependency in `build.gradle.kts` (with `id("io.kmpkt.di")` applied, `MessengerPersistenceComponent` is discovered automatically):

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-messenger-persistence:0.1.0")
}
```

## DI Setup

Bind an `EnvelopeSerializer` implementation, declare a `PersistentTransport` via `transport<PersistentTransport>("outbox")`, and attach `TransactionMiddleware` in `src/di/kotlin`:

```kotlin
// src/di/kotlin/AppConfiguration.kt
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.persistence.middleware.TransactionMiddleware
import io.kmpkt.messenger.persistence.serialization.EnvelopeSerializer
import io.kmpkt.messenger.persistence.transport.PersistentTransport

class AppConfiguration : Configuration<MyApp> {
    override fun ContainerBuilder.configure() {
        service<MyEnvelopeSerializer> { bind<EnvelopeSerializer>() }

        messenger {
            val outbox = transport<PersistentTransport>("outbox")
            bus("command") {
                middleware<TransactionMiddleware>()
                defaultTransport = outbox
            }
        }
    }
}
```

## Usage

Implement `EnvelopeSerializer` in `src/main/kotlin`, then dispatch through your configured bus; `PersistentTransport` enqueues and acknowledges envelopes inside `WorkQueue` transactions:

```kotlin
// src/main/kotlin/MyEnvelopeSerializer.kt
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.persistence.serialization.EnvelopeSerializer

class MyEnvelopeSerializer : EnvelopeSerializer {
    override fun serialize(envelope: Envelope): String = TODO("Encode envelope")
    override fun deserialize(payload: String): Envelope = TODO("Decode envelope")
}
```

Provision the underlying queue tables via `SetupTransportsCommand` (`messenger:setup-transports`) or `PersistentTransport.setup()`. See [kmpkt-messenger](../kmpkt-messenger/README.md#consuming-queued-transports) for running CLI or in-process queue workers.

## Extending

### Custom `WorkQueue`

`WirePersistentTransportsPass` automatically provisions a `WorkQueue` via `Persistence.workQueue(Outbox.name)`. Override it in `src/di/kotlin` under the transport marker's `name` only when you want custom storage behavior for that queue — for example, a dedicated SQL table and `pageSize` via `JdbcWorkQueue`, priority-ordered `claim()`, or a metrics decorator:

```kotlin
fun highPrioQueue(dataSource: DataSource): WorkQueue =
    JdbcWorkQueue(dataSource = dataSource, queue = "outbox_high_prio", pageSize = 500)

factory(::highPrioQueue) {
    bind<WorkQueue>(Outbox.name)
}
```

### Leader-locked consumption

Inject `WorkLock` (automatically bound to `PersistenceWorkLock`) to wrap polling `Work` in `LockedWork`:

```kotlin
class OutboxConsumer(lock: WorkLock, work: TransportWork) {
    val lockedWork = LockedWork("outbox-lock", lock, work)
}
```
