# messenger

A multi-bus messaging example built with `kmpkt-messenger`, `kmpkt-worker`, and `kmpkt-di`. It demonstrates typed message buses with distinct handler policies, correlation middleware, type-safe `TransportRef` routing, priority-ordered event listeners, automatic retries, dead-lettering, and managed background workers.

## Flow

```mermaid
flowchart TD
    Runner["DemoInteractionRunner"] -->|PlaceOrder / SendReceipt| CmdBus["MessageBus('commands')"]
    CmdBus -->|PlaceOrder via Sync| PlaceH["PlaceOrderHandler"]
    PlaceH -->|OrderPlaced| EvtBus["MessageBus('events')"]
    EvtBus -->|priority 10| AuditH["AuditOrderPlacedHandler"]
    EvtBus -->|priority 0| AnalyticsH["AnalyticsOrderPlacedHandler"]
    CmdBus -->|SendReceipt via AsyncJobs| AsyncT["InMemoryTransport('async-jobs')"]
    AsyncT --> Worker["TransportWork / ManagedWorker"]
    Worker -->|retry maxRetries=1| CmdBus
    CmdBus -->|SendReceipt| ReceiptH["SendReceiptHandler"]
    ReceiptH --> Mailer["SimulatedReceiptMailer"]
    Worker -->|retries exhausted| DLQ["ObservableInMemoryTransport('dead-letter')"]
```

## How It Works

### 1. Define Messages and Middleware

Commands (`PlaceOrder`, `SendReceipt`) and events (`OrderPlaced`) implement `Message`. Every envelope entering the `"commands"` bus passes through `CorrelationMiddleware`, which attaches a `CorrelationStamp` if one is not already present so initial dispatches and async retries can be traced in logs.

### 2. Configure Buses, Transports, and Retries

`MessengerExampleConfiguration` configures `workers { autoStart = true }`, declares three transports (`deadLetter` for `"dead-letter"`, `asyncJobs` for `"async-jobs"` with `MultiplierRetryStrategy(maxRetries = 1, delay = 10.milliseconds)`, and `sync` for `"sync"`), and registers two buses:

- **`commands`** (the default bus): runs `CorrelationMiddleware`, handles `PlaceOrder` synchronously, and routes `SendReceipt` to `asyncJobs`.
- **`events`**: uses `HandlerPolicy.MULTIPLE` so multiple listeners react to `OrderPlaced` in priority order (`AuditOrderPlacedHandler` at priority `10` before `AnalyticsOrderPlacedHandler` at priority `0`).

```kotlin
messenger {
    val deadLetter = transport<ObservableInMemoryTransport>("dead-letter")
    val asyncJobs = transport<InMemoryTransport>("async-jobs") {
        retry<MultiplierRetryStrategy> {
            maxRetries = 1
            delay = 10.milliseconds
        }
        failureTransport = deadLetter
    }
    val sync = transport<SyncTransport>("sync")

    defaultBus =
        bus("commands") {
            defaultTransport = sync
            middleware<CorrelationMiddleware>()
            route<SendReceipt>(asyncJobs)
        }

    bus("events") {
        policy = HandlerPolicy.MULTIPLE
        defaultTransport = sync
    }
}
```

### 3. Run Background Workers Before Client Traffic

`TransportWork` consumes messages from `"async-jobs"`, dispatches them back through `"commands"`, and forwards exhausted messages to `"dead-letter"`. `WorkerComponent` wraps it into a `ManagedWorker`, and `workers { autoStart = true }` registers `WorkerRunner` (priority `100`) to start all workers before `DemoInteractionRunner` (priority `0`) begins sending traffic.

### 4. Observe Sync Fan-Out, Retries, and Dead-Lettering

`DemoInteractionRunner` dispatches three commands:

- `PlaceOrder("ord-42", 99, "alice")` runs inline and fans out `OrderPlaced` to both audit and analytics handlers.
- `SendReceipt("ord-42")` fails on its first attempt in `SimulatedReceiptMailer` (simulating a transient SMTP timeout) and succeeds on retry `#2`.
- `SendReceipt("ord-broken")` fails permanently, exhausts its retry budget, and is routed to `"dead-letter"` (`ObservableInMemoryTransport`).

## Project Layout

| File | Role |
| --- | --- |
| [`PlaceOrder.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/domain/command/PlaceOrder.kt), [`SendReceipt.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/domain/command/SendReceipt.kt), [`OrderPlaced.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/domain/event/OrderPlaced.kt) | Command and event messages |
| [`CorrelationMiddleware.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/inbound/bus/CorrelationMiddleware.kt) | Middleware attaching `CorrelationStamp` |
| [`PlaceOrderHandler.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/application/handler/PlaceOrderHandler.kt), [`SendReceiptHandler.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/application/handler/SendReceiptHandler.kt) | Command handlers |
| [`AuditOrderPlacedHandler.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/application/handler/AuditOrderPlacedHandler.kt), [`AnalyticsOrderPlacedHandler.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/application/handler/AnalyticsOrderPlacedHandler.kt) | Priority-ordered event handlers |
| [`SimulatedReceiptMailer.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/outbound/mail/SimulatedReceiptMailer.kt), [`ObservableInMemoryTransport.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/outbound/transport/ObservableInMemoryTransport.kt), [`ObservableLogger.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/outbound/logger/ObservableLogger.kt) | Outbound mail simulator, dead-letter transport, and in-memory log |
| [`MessengerExampleConfiguration.kt`](core/src/di/kotlin/io/kmpkt/example/messenger/di/MessengerExampleConfiguration.kt) | Compile-time DI, bus, transport, and worker wiring |
| [`DemoInteractionRunner.kt`](core/src/main/kotlin/io/kmpkt/example/messenger/inbound/runner/DemoInteractionRunner.kt) | Demo client interaction runner |
| [`Main.kt`](app/src/main/kotlin/io/kmpkt/example/messenger/Main.kt), [`MessengerAppTest.kt`](app/src/test/kotlin/io/kmpkt/example/messenger/MessengerAppTest.kt) | Application entrypoint and test |

## Running & Testing

```bash
./gradlew :examples:messenger:app:test
```
