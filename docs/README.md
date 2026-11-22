# KmpKt Documentation

> **Active Development:** KmpKt is in active early development (pre-1.0); APIs, module boundaries, and configuration DSLs may still change between releases.

Welcome to KmpKt — compact CQRS, event sourcing, and typed messaging for Kotlin, built on
compile-time dependency injection.

## Guides

1. [Why KmpKt?](why.md) - Background, Symfony inspiration, and credits.
2. [Getting Started](getting-started.md) — Adding KmpKt to a Gradle project, setting up
   `io.kmpkt.di`, and wiring your first `KmpKtApplication`.
3. [Module Guide](modules.md) — What each module does and when to include it.
4. [CQRS & Event Sourcing](cqrs-and-event-sourcing.md) — How commands, queries, events,
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
