# Messenger Tooling — Multi-Bus, Middleware, Retries, and Workers

An end-to-end example showing `kmpkt-messenger`, `kmpkt-worker`, and `kmpkt-di` working together
with compile-time wiring and zero runtime reflection.

## Domain

An order confirmation and receipt delivery pipeline: placing an order (`PlaceOrder`) is handled
synchronously, `OrderPlaced` fans out to audit and analytics listeners in priority order, and
sending receipts (`SendReceipt`) runs asynchronously in a background worker where transient SMTP
outages are retried and permanent bounces move to a dead-letter queue.

## What this example shows

- **Multiple typed buses with distinct policies.** `commands` runs with `HandlerPolicy.SINGLE` and
  a custom [`CorrelationMiddleware`](core/src/main/kotlin/io/kmpkt/example/messenger/inbound/bus/CorrelationMiddleware.kt)
  attaching `CorrelationStamp`; `events` runs with `HandlerPolicy.MULTIPLE` and priority-ordered
  handlers (`AuditOrderPlacedHandler` before `AnalyticsOrderPlacedHandler`).
- **Per-message transport routing.** `PlaceOrder` executes synchronously on `sync`, while
  `SendReceipt` is routed asynchronously to `InMemoryTransport("async-jobs")`.
- **Automatic background worker wiring.** `TransportWork` is wrapped into a `ManagedWorker` by
  `WorkerComponent` and injected as `ManagedWorker` without manual boilerplate.
- **Two-runner process vs. demo separation.**
  - [`MessengerWorkerRunner`](core/src/main/kotlin/io/kmpkt/example/messenger/inbound/runner/MessengerWorkerRunner.kt)
    (`priority = 100`) starts all registered `ManagedWorker` instances like a backend service.
  - [`DemoInteractionRunner`](core/src/main/kotlin/io/kmpkt/example/messenger/inbound/runner/DemoInteractionRunner.kt)
    (`priority = 0`) simulates an external client dispatching commands while the worker runs,
    verifying side effects via [`ObservableLogger`](core/src/main/kotlin/io/kmpkt/example/messenger/outbound/logger/ObservableLogger.kt).
- **Retries and dead-lettering.** `ord-42` fails on its first SMTP attempt, is retried via
  `MultiplierRetryStrategy`, and succeeds on attempt 2 (`ord-42#2`). `ord-broken` exhausts
  retries and lands on `dead-letter` with `SentToFailureTransportStamp`.
- **Transitive `di` module resolution.** `app` only depends on `:examples:messenger:core` and
  starts via `KmpKt.run<MessengerApp>(args)`.

## Project layout

| Module | Contents |
| --- | --- |
| `core` | [Messages](core/src/main/kotlin/io/kmpkt/example/messenger/domain), [middleware](core/src/main/kotlin/io/kmpkt/example/messenger/inbound/bus/CorrelationMiddleware.kt), [handlers](core/src/main/kotlin/io/kmpkt/example/messenger/application/handler), [runners](core/src/main/kotlin/io/kmpkt/example/messenger/inbound/runner), and [configuration](core/src/di/kotlin/io/kmpkt/example/messenger/di/MessengerExampleConfiguration.kt) |
| `app` | [Main.kt](app/src/main/kotlin/io/kmpkt/example/messenger/Main.kt) and [MessengerAppTest.kt](app/src/test/kotlin/io/kmpkt/example/messenger/MessengerAppTest.kt) |

## Run it

```bash
./gradlew :examples:messenger:app:test
```
