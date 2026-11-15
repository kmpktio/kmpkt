# kmpkt Documentation

Welcome to `kmpkt` — compact CQRS, event sourcing, and typed messaging for Kotlin, built on
compile-time dependency injection.

## Guides

1. [Getting Started](getting-started.md) — Adding `kmpkt` to a Gradle project, setting up
   `io.kmpkt.di`, and wiring your first `KmpKtApplication`.
2. [Module Guide](modules.md) — What each of the 20 modules does and when to include it.
3. [CQRS & Event Sourcing](cqrs-and-event-sourcing.md) — How commands, queries, events,
   aggregates, policies, and projections fit together, and when to use CQRS with or without
   event sourcing.

## Examples

- [Todo List — CQRS without Event Sourcing](../examples/todo-cqrs/README.md) — Minimal
  application using `CommandBus`, `QueryBus`, `EventBus`, and `KmpKt.run`.
- [Todo List — CQRS + Event Sourcing](../examples/todo-event-sourced/README.md) — Same domain
  rebuilt with event-sourced aggregates, projections, and policies.
- [Modulith Todo Example](../examples/modulith/README.md) — Independent `todos`, `audit`,
  `telemetry`, and `features` modules composed with compile-time decorators.
- [Messenger Tooling Example](../examples/messenger/README.md) — Multi-bus routing, custom
  middleware stamps, priority handlers, async worker retries, and dead-lettering.
