# Module Guide

`kmpkt` is split into focused modules so applications only pull in the capabilities they need.

## Dependency Injection

- [`kmpkt-di`](../kmpkt-di/README.md) — `KmpKtApplication` and `ApplicationRunner` runtime
  contracts.
- [`kmpkt-di-compiler-api`](../kmpkt-di-compiler-api/README.md) — Container IR, `Component`,
  `BuilderPass`, and compiler pipeline.
- [`kmpkt-di-compiler`](../kmpkt-di-compiler/README.md) — KSP symbol processor and KotlinPoet
  code generator.
- [`kmpkt-di-compiler-plugin`](../kmpkt-di-compiler-plugin/README.md) — Gradle plugin
  (`io.kmpkt.di`) configuring `di` source sets and KSP.

## Messaging & Workers

- [`kmpkt-worker`](../kmpkt-worker/README.md) — Managed background coroutine workers
  (`ManagedWorker`, `WorkLoop`, retry strategies).
- [`kmpkt-messenger`](../kmpkt-messenger/README.md) — Core `MessageBus`, `Middleware`,
  `Transport`, routing, and retries.
- [`kmpkt-messenger-outbox-relay`](../kmpkt-messenger-outbox-relay/README.md) — `OutboxRelayMiddleware`
  for forwarding envelopes received from a durable outbox `Transport` to a target `Transport`.
- [`kmpkt-command-bus`](../kmpkt-command-bus/README.md) — `Command`, `CommandHandler`, and
  `CommandBus` (`HandlerPolicy.SINGLE`).
- [`kmpkt-event-bus`](../kmpkt-event-bus/README.md) — `Event`, `EventHandler`, and `EventBus`
  (`HandlerPolicy.MULTIPLE`).
- [`kmpkt-query-bus`](../kmpkt-query-bus) — `Query<R>`, `QueryHandler<Q, R>`, and `QueryBus`
  (`HandlerPolicy.SINGLE`).

## Persistence

- [`kmpkt-persistence`](../kmpkt-persistence/README.md) — Storage ports (`AppendLog`,
  `KeyValueStore`, `WorkQueue`) and `InMemoryPersistence`.
- [`kmpkt-persistence-jdbc`](../kmpkt-persistence-jdbc/README.md) — JDBC `Persistence` for
  SQLite and PostgreSQL with Flyway migrations.
- [`kmpkt-messenger-persistence`](../kmpkt-messenger-persistence/README.md) —
  `PersistentTransport` and `TransactionMiddleware` for durable queues.

## Event Sourcing & Aggregates

- [`kmpkt-aggregate`](../kmpkt-aggregate/README.md) — `AggregateRoot`, `BaseAggregateRoot`,
  `AggregateRepository`, `DomainEvent`.
- [`kmpkt-event-store`](../kmpkt-event-store/README.md) — Global append-only `EventStore`,
  `DefaultAggregateRepository`, `EventSerializer`.
- [`kmpkt-aggregate-snapshot`](../kmpkt-aggregate-snapshot/README.md) — `Snapshotable`,
  `SnapshotStrategy`, and `SnapshotAggregateRepository`.
- [`kmpkt-serialization-kotlinx`](../kmpkt-serialization-kotlinx/README.md) —
  `KotlinxEventSerializer` built on `kotlinx.serialization`.

## Policies & Projections

- [`kmpkt-policy`](../kmpkt-policy/README.md) — `Policy` — reacts to stored events and
  dispatches follow-up commands.
- [`kmpkt-projection`](../kmpkt-projection/README.md) — `Projector` and `ProjectionStore` —
  builds read models from stored events.

## Framework Bridges

- [`kmpkt-clikt-bridge`](../kmpkt-clikt-bridge/README.md) — Clikt CLI runner integration for
  `kmpkt-di`.
- [`kmpkt-ktor-bridge`](../kmpkt-ktor-bridge/README.md) — Ktor server lifecycle and
  `HttpRoutes` integration.
