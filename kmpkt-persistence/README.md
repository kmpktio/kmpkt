# kmpkt-persistence

The storage ports that span event log, projection positions, policy event tracking, and process locking, plus an in-memory reference implementation.

## Scope

- `Persistence`: single entry point exposing `events` (`EventStore`), `projectionPositions` (`ProjectionPositionStore`), `processedEvents` (`ProcessedEventStore`), and `underLock(name, block)` so an adapter can keep cross-store operations atomic in one transaction/connection.
- `ProjectionPositionStore` & `ProjectionState`: tracks each projection's `position` and optional `rejectedAt` timestamp so projections can resume or reset cleanly.
- `ProcessedEventStore`: tracks per-event claim, completion, failure, and garbage collection for sequential policy runners (`setup`, `claimNext`, `complete`, `fail`, `collectGarbage`).
- `InMemoryPersistence`, `InMemoryProjectionPositionStore`, `InMemoryProcessedEventStore`: coroutine-safe in-memory implementations for tests and single-process setups.

## Adapters

- [`kmpkt-persistence-jdbc`](../kmpkt-persistence-jdbc): JDBC `DataSource`-backed implementation of `Persistence`.
