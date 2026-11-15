# CQRS & Event Sourcing Architecture

`kmpkt` separates **CQRS / Messaging** from **Event Sourcing**. You can adopt only the buses
for a clean command/query separation, or add the event store, projections, and policies on top.

## Option 1: CQRS without Event Sourcing

When your domain state is simple CRUD or stored directly in a table/document:

```text
Caller -> CommandBus -> CommandHandler -> Repository (state)
                     \-> EventBus (optional notifications)
Caller -> QueryBus   -> QueryHandler   -> Read Store / Repository
```

- **Modules**: `kmpkt-di`, `kmpkt-command-bus`, `kmpkt-query-bus`, `kmpkt-event-bus`
- **Example**: [`examples/todo-cqrs`](../examples/todo-cqrs)

## Option 2: CQRS with Event Sourcing

When you want an append-only audit log of domain events, decoupled read models, and reactive
policies:

```text
Caller -> CommandBus -> CommandHandler -> AggregateRepository -> EventStore
                                                                     |
                         +-------------------------------------------+
                         v                                           v
               ProjectionTransport (Projector)             PolicyTransport (Policy)
                         |                                           |
                         v                                           v
                    Read Model                                  CommandBus
```

- **Write side**: `CommandHandler` loads an `AggregateRoot` via `AggregateRepository`, invokes
  domain behavior, and persists new `DomainEvent`s to `EventStore`.
- **Read side**: `Projector`s consume events from `EventStore` via `ProjectionTransport` and
  update read models (`ProjectionStore` or SQL tables). `QueryHandler`s read from those models.
- **Reactions**: `Policy` instances react to `DomainEvent`s via `PolicyTransport` and dispatch
  follow-up commands on `CommandBus`.
- **Example**: [`examples/todo-event-sourced`](../examples/todo-event-sourced)

## Modularity & Cross-Cutting Concerns

As an application grows, features like auditing, telemetry, or feature flags can live in
separate modules and wrap core ports at compile time using `kmpkt-di` decorators:

- **Example**: [`examples/modulith`](../examples/modulith)
