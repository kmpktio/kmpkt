# kmpkt-event

`Event`, its handler, and the bus that publishes one to the other. The counterpart to
[`kmpkt-command-bus`](../kmpkt-command-bus): a command asks for something and has exactly one handler, an event
reports something and has as many handlers as care.

This module exists so that **CQRS without event sourcing works**. Running a classic event bus without an event
store is a legitimate way to use the family, so the two worlds are layered rather than merged:
[`kmpkt-aggregate`](../kmpkt-aggregate) adds `DomainEvent : Event` on top of this, with the aggregate,
version and time that an append-only log needs.

## Scope

- `Event`: the marker for something that happened. It is a [`kmpkt-messenger`](../kmpkt-messenger)
  `Message`, which is what lets it travel over a bus.
- `EventHandler<E>`: reacts to one. Contravariant, so a handler written against a supertype serves its
  subtypes. Unconstrained on purpose - a handler may do whatever it likes. It is a `MessageHandler<E, Unit>`
  for its event, so a bus takes it as it is.
- `EventBus`: publishes onto a `MessageBus`, so events get that bus's middleware, routing and retries.

## Usage

```kotlin
data class ItemChecked(val name: String) : Event

class NotifyOnItemChecked(...) : EventHandler<ItemChecked> {
    override suspend fun handle(event: ItemChecked) { ... }
}

// wherever the bus is built - it has to allow HandlerPolicy.MULTIPLE
bus("event") { handler(NotifyOnItemChecked(...)) }

EventBus(messageBus).publish(ItemChecked("Milch"))
```

## Which to use under event sourcing

With an event store, **nothing publishes**: the store is the append-only log, and a
[`Policy`](../kmpkt-policy) (event in, commands out) or a [`Projector`](../kmpkt-projection) (event in,
projection changes out) reads events back out of it rather than being handed them. Both are constrained
versions of `EventHandler`, and the constraint is the point - a side effect expressed as a command is
individually retryable, replayable and testable without an event store.

A plain `EventHandler` alongside all that is still allowed, and an audit log or an outgoing webhook is a
reasonable reason to want one. It is a choice, not a mistake.
