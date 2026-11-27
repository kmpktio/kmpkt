# kmpkt-messenger-outbox-relay

Forwards envelopes received from a source `Transport` (such as a durable outbox `PersistentTransport`) directly to a target `Transport` without invoking downstream message handlers.

## Building Blocks

| Symbol | Description |
| --- | --- |
| `OutboxRelayMiddleware` | Middleware forwarding matching source envelopes directly to a target transport |
| `OutboxRelayComponent` | DI component registering qualified `Middleware` bindings for each declared relay |
| `OutboxRelayOptions` | DSL options for declaring `relay(from, to)` pairs via `TransportRef` handles |
| `outboxRelay` | `ContainerBuilder` DSL entry point for configuring `OutboxRelayComponent` |

## Installation

Add the dependency in `build.gradle.kts` (with `id("io.kmpkt.di")` applied, `OutboxRelayComponent` is discovered automatically):

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-messenger-outbox-relay:0.1.0")
}
```

## DI Setup

Register source and target transports in `messenger`, wire the relay in `outboxRelay` using their `TransportRef` handles, and attach the qualified `Middleware` (`"<from>-to-<to>"`) to a relay bus in `src/di/kotlin`:

```kotlin
// src/di/kotlin/AppConfiguration.kt
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.messenger.di.maxRetries
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.outbox.relay.di.outboxRelay
import io.kmpkt.messenger.persistence.transport.PersistentTransport
import io.kmpkt.messenger.retry.MultiplierRetryStrategy

class AppConfiguration : Configuration<MyApp> {
    override fun ContainerBuilder.configure() {
        lateinit var outbox: TransportRef<PersistentTransport>
        lateinit var broker: TransportRef<MyBrokerTransport>

        messenger {
            outbox = transport<PersistentTransport>("outbox") {
                retry<MultiplierRetryStrategy> { maxRetries = 5 }
            }
            broker = transport<MyBrokerTransport>("broker")

            bus("relay") {
                defaultMiddleware {
                    send = false
                    handle = false
                }
                middleware<Middleware>("outbox-to-broker")
            }
        }

        outboxRelay {
            relay(from = outbox, to = broker)
        }
    }
}
```

## Usage

Pair the `"relay"` bus with `TransportWork` (optionally wrapped in `LockedWork` for single-active-relay ordering) consuming the `"outbox"` transport in `src/main/kotlin`:

```kotlin
// src/main/kotlin/OutboxRelayRunner.kt
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.messenger.worker.TransportWork
import io.kmpkt.worker.LockedWork
import io.kmpkt.worker.Work
import io.kmpkt.worker.WorkLock

fun createRelayWork(
    outbox: Transport,
    relayBus: MessageBus,
    lock: WorkLock,
): Work = LockedWork(
    lockName = "outbox-relay",
    lock = lock,
    delegate = TransportWork(mapOf("outbox" to outbox), relayBus),
)
```

## Extending

### Filtering or enrichment

Place another `Middleware` before `OutboxRelayMiddleware` on the relay bus to filter, transform, or attach stamps before forwarding:

```kotlin
bus("relay") {
    defaultMiddleware { send = false; handle = false }
    middleware<RelayFilterMiddleware>()
    middleware<Middleware>("outbox-to-broker")
}
```

### Multiple relays

Declare additional `relay(from, to)` entries inside `outboxRelay`; each binds a separate `Middleware` qualified as `"<from>-to-<to>"`:

```kotlin
outboxRelay {
    relay(from = Outbox, to = Broker)
    relay(from = AuditOutbox, to = AnalyticsBroker)
}
```
