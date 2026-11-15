# kmpkt-aggregate-snapshot

Optional in-stream snapshotting for [`kmpkt-event-store`](../kmpkt-event-store): a `SnapshotEvent`
recorded directly in the aggregate's event stream so loading replays only from the latest
snapshot forward. Small aggregates with few events can use `DefaultAggregateRepository` instead.

## Scope

- `Snapshotable<A, S>` / `SnapshotableAggregateRoot<A, S>`: aggregate contract for producing
  and restoring snapshots.
- `SnapshotEvent<A, S>`: domain event carrying the captured snapshot state.
- `SnapshotStrategy`: decides when a save records a new snapshot (e.g.
  `VersionIntervalSnapshotStrategy(interval = 50)`).
- `SnapshotAggregateRepository<A, S>`: decorates or wraps loading/saving with automatic
  snapshot recording and replay from the latest `SnapshotEvent`.
