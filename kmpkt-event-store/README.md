# kmpkt-event-store

The append-only log: one global `EventStore` of streams, with optimistic concurrency — and no assumption about
where any of it is stored. An in-memory implementation is included as the default. The aggregates and events
it stores are [`kmpkt-aggregate`](../kmpkt-aggregate); everything else is a separate, optional module:
snapshotting ([`kmpkt-aggregate-snapshot`](../kmpkt-aggregate-snapshot)), JSON (de)serialization
([`kmpkt-serialization-kotlinx`](../kmpkt-serialization-kotlinx)), durable storage
([`kmpkt-persistence-jdbc`](../kmpkt-persistence-jdbc)).

## Scope

- `EventStore`: port - **one global append-only log of streams**, one instance for every aggregate type. A stream is identified by `(AggregateType, AggregateId)`.
  `loadFrom`'s positions are a single order over the whole log, which is what lets a subscriber spanning
  several aggregates see their events in the order they happened. `InMemoryEventStore` is the default,
  non-durable implementation.
- `DefaultAggregateRepository<A>`: implements `kmpkt-aggregate`'s `AggregateRepository` by always replaying
  full history. `SnapshotAggregateRepository` (in `kmpkt-aggregate-snapshot`) is for aggregates with enough
  events that replaying all of them on every load gets expensive.
- `StoredEvent`: an event as the log holds it, with its global position.

See [`kmpkt-aggregate`](../kmpkt-aggregate) for how an aggregate and its events are written.

## Persisting events

`EventSerializer` is the port a durable store needs: it turns a `DomainEvent` into a `SerializedEvent`
(`type` + `payload`) and back. This module deliberately depends on no serialization library, so the choice
stays with the application — [`kmpkt-serialization-kotlinx`](../kmpkt-serialization-kotlinx)
is one implementation, your own is just as valid.
