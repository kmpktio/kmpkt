# kmpkt-aggregate

The domain side of event sourcing: aggregates, their typed ids, the events they record, and the repository port
that loads and saves them. No persistence, no store, no serialization library — those live in
[`kmpkt-event-store`](../kmpkt-event-store) and its adapters.

Separate from the store because an aggregate is domain rather than persistence, and because anything that only
needs an `AggregateId` should not have to drag an event store along with it.

## Scope

- `AggregateRoot<A>` / `BaseAggregateRoot<A>`: records domain events, replays them to rebuild state, no
  persistence concerns of its own.
- `AggregateId<A>`: a typed id per aggregate type, so ids of different aggregates can't be mixed up.
  `AggregateUuid.generate()` for a fresh random id; `fromName(aggregateType, name)` derives a stable one from
  an external id (e.g. a CRM account id) instead of wrapping it directly, so the id stays a real UUID no matter
  the external id's format.
- `AggregateType<A>`: the name an aggregate's streams are filed under in the log, e.g.
  `AggregateType<Counter>("Counter")`. Declared once next to the aggregate and handed to its repository.
- `DomainEvent<A>`: an [`Event`](../kmpkt-event-bus) that knows its aggregate, version and time — which is what
  an append-only log needs to store and replay it. Deliberately not `sealed`: concrete event types live in
  whatever module defines the aggregate, not here.
- `AggregateRepository<A>`: the port for loading and saving one. Implementations are in
  `kmpkt-event-store` (`DefaultAggregateRepository`, full replay) and `kmpkt-aggregate-snapshot`
  (`SnapshotAggregateRepository`, for aggregates with enough events that full replay gets expensive).
- `AggregateFactory<A>`, `AggregateNotFound`, `ConcurrencyConflict`.

## Usage

```kotlin
class Counter private constructor(id: AggregateId<Counter>?) : BaseAggregateRoot<Counter>(id) {
    var value: Int = 0
        private set

    fun increment() = recordThat(CounterIncremented(aggregate = id, version = version + 1, occurredAt = Instant.now()))

    override fun updateState(event: DomainEvent<Counter>) {
        when (event) {
            is CounterIncremented -> value += 1
        }
    }

    companion object {
        fun new(): Counter = Counter(null)
    }
}
```

Not every event needs to carry who caused it — that's a domain decision, not something this module tracks for
you. Add a field to the concrete event when it matters (e.g. `correctedBy: UserEmail`), and nothing when it
doesn't.

## Test fixtures

`Counter` and `Gauge`, two minimal aggregates, ship as Gradle test fixtures, because every module downstream
of this one needs an aggregate to test against and copies of one would drift:

```kotlin
testImplementation(testFixtures(project(":kmpkt-aggregate")))
```
