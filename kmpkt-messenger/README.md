# kmpkt-messenger

Core message bus, envelope and stamp model, middleware pipeline, transport routing, retry handling, and consumer `Work` for KmpKt applications. Designed to be used directly or wired at compile time via its `di` source set.

## Building Blocks

### Buses & Catalog

| Symbol | Purpose |
| --- | --- |
| `MessageBus` | Core bus interface for dispatching, sending, and asking messages |
| `DefaultMessageBus` | Executes an ordered `Middleware` chain |
| `RoutableMessageBus` | Routes envelopes to the bus named by `BusNameStamp` |
| `MessengerCatalog` | Holds registered buses, transports, failure transports, and the default bus |

### Messages, Handlers & Exceptions

| Symbol | Purpose |
| --- | --- |
| `Message`, `MessageHandler`, `Envelope`, `Stamp` | Core message, handler, envelope wrapper, and metadata marker types |
| `HandlerPolicy`, `HandlerRegistry`, `DefaultHandlerRegistry` | Handler lookup and single-vs-multiple invocation policies |
| `HandlerFailedException`, `NoHandlerForMessageException` | Exceptions thrown on handler failure or missing registration |

### Middlewares

| Symbol | Purpose |
| --- | --- |
| `Middleware`, `MiddlewareChain` | Intercepts envelope dispatch before and after downstream execution |
| `AddBusNameStampMiddleware` | Attaches `BusNameStamp` if absent |
| `SendMessageMiddleware` | Routes envelopes via `RouteStamp` or `TransportRegistry` when not already received |
| `FailedMessageProcessingMiddleware` | Restores `ReceivedStamp` when re-consuming from a failure transport |
| `RetryMiddleware` | Re-queues failed envelopes with `RedeliveryStamp` and `DelayStamp` |
| `HandleMessageMiddleware` | Invokes matching handlers under `HandlerPolicy` and attaches `HandledStamp` |
| `DispatchAfterCurrentBusMiddleware` | Defers dispatches carrying `DispatchAfterCurrentBusStamp` until the outer bus completes |
| `RateLimitedMiddleware` | Acquires permits from named `RateLimiter` instances for `RateLimitedStamp` |
| `LoggingMiddleware` | Logs dispatch entry, completion, and failures via SLF4J |

### Stamps

| Symbol | Purpose |
| --- | --- |
| `AsyncStamp`, `BusNameStamp`, `DelayStamp`, `DispatchAfterCurrentBusStamp`, `DispatchedStamp`, `RateLimitedStamp`, `ReceivedStamp`, `RedeliveryStamp`, `SentToFailureTransportStamp` | Built-in routing, scheduling, retry, and lifecycle metadata |
| `HandledStamp` | Records handler execution and return values |
| `RouteStamp`, `SentStamp` | Records explicit transport routing and delivery |

### Transports, Retries & Worker

| Symbol | Purpose |
| --- | --- |
| `Transport`, `SyncTransport`, `InMemoryTransport`, `ListableTransport`, `QueuedEnvelope`, `SetupableTransport`, `TransportFactory` | Transport abstractions and built-in sync/in-memory implementations |
| `TransportRegistry`, `DefaultTransportRegistry` | Maps message types to target transports |
| `RetryStrategy`, `MultiplierRetryStrategy`, `RetryDecider`, `RecoverableException`, `UnrecoverableException`, `RetryScheduledException` | Retry policies, backoff strategies, and exception classification |
| `TransportWork` | `Work` implementation polling prioritized transports onto `MessageBus` |

### DI Configuration

| Symbol | Purpose |
| --- | --- |
| `MessengerComponent`, `messenger`, `MessengerOptions`, `BusRef`, `TransportRef`, `TransportOptions`, `StrategyOptions`, `BusOptions`, `DefaultMiddlewareOptions`, `MessageHandlerTag`, `HandlerRegistryTag` | Compile-time DI component, DSL configuration, and handler tags |
| `RegisterMessageHandlersPass` | `BuilderPass` that wires tagged handlers into each bus's `HandlerRegistry` |

## Installation

Add the dependency in `build.gradle.kts` (with `id("io.kmpkt.di")` applied, `MessengerComponent` is discovered automatically):

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-messenger:0.1.0")
}
```

## DI Setup

Configure transports, retry strategies, routing, and buses in `src/di/kotlin`:

```kotlin
// src/di/kotlin/AppConfiguration.kt
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.messenger.di.delay
import io.kmpkt.messenger.di.maxRetries
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.logging.LoggingMiddleware
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.retry.MultiplierRetryStrategy
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.messenger.transport.SyncTransport
import kotlin.time.Duration.Companion.seconds

class AppConfiguration : Configuration<MyApp> {
    override fun ContainerBuilder.configure() {
        discover("com.example.app") {
            services<MessageHandler<*, *>>()
        }

        messenger {
            val failed = transport<InMemoryTransport>("failed")
            val async = transport<InMemoryTransport>("async") {
                retry<MultiplierRetryStrategy> {
                    maxRetries = 3
                    delay = 1.seconds
                }
                failureTransport = failed
            }
            val sync = transport<SyncTransport>("sync") {
                failureTransport = failed
            }

            defaultBus = bus("default") {
                defaultTransport = sync
                route<Greet>(async)
                middleware<LoggingMiddleware>()
            }
        }
    }
}
```

## Usage

Define messages and handlers in `src/main/kotlin`, then inject and invoke `MessageBus`:

```kotlin
// src/main/kotlin/Greet.kt
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler

data class Greet(val name: String) : Message

class GreetHandler : MessageHandler<Greet, Unit> {
    override suspend fun invoke(message: Greet) {
        println("Hello, ${message.name}")
    }
}

class GreetingService(private val bus: MessageBus) {
    suspend fun greet(name: String) {
        bus.send(Greet(name))
    }
}
```

For standalone usage without DI, wire `DefaultHandlerRegistry` and `DefaultMessageBus` directly:

```kotlin
val registry = DefaultHandlerRegistry().apply {
    register(Greet::class, GreetHandler())
}
val bus = DefaultMessageBus(
    middleware = listOf(HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC())),
    handlerRegistry = registry,
)
bus.send(Greet("world"))
```

## Extending

### Custom `Middleware`

Implement `Middleware` and register it on a bus with `middleware<TimingMiddleware>()`:

```kotlin
class TimingMiddleware : Middleware {
    override suspend fun handle(envelope: Envelope, next: MiddlewareChain): Envelope {
        val start = System.nanoTime()
        return try {
            next(envelope)
        } finally {
            println("Handled in ${System.nanoTime() - start} ns")
        }
    }
}
```

### Custom `Stamp`

Implement `Stamp` to attach custom metadata to an `Envelope`:

```kotlin
data class CorrelationStamp(val correlationId: String) : Stamp

fun Envelope.correlationId(): String? = last<CorrelationStamp>()?.correlationId
```

### Custom `Transport`

Implement `Transport` (and optionally `SetupableTransport` or `ListableTransport`), then register it with `transport<CustomTransport>("name")`:

```kotlin
class CustomTransport : Transport {
    override suspend fun send(envelope: Envelope): Envelope = envelope
    override suspend fun receive(): List<Envelope> = emptyList()
    override suspend fun ack(envelope: Envelope) {}
    override suspend fun reject(envelope: Envelope) {}
}
```

### Custom `RetryStrategy`

Implement `RetryStrategy` to control retry eligibility and backoff duration:

```kotlin
class FixedRetryStrategy(private val maxRetries: Int = 3) : RetryStrategy {
    override fun isRetryable(envelope: Envelope, failure: Throwable): Boolean =
        (envelope.last<RedeliveryStamp>()?.retryCount ?: 0) < maxRetries

    override fun waitingTime(envelope: Envelope, failure: Throwable): Duration = 2.seconds
}
```

### Bus targeting & ordering

Tag handlers with `MessageHandlerTag` in `src/di/kotlin` to assign a bus and invocation priority (highest first):

```kotlin
service<GreetHandler> {
    tag(MessageHandlerTag(bus = "default", priority = 10))
}
```
