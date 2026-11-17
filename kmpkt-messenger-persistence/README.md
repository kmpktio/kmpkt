# kmpkt-messenger-persistence

Persistent [`Transport`](../kmpkt-messenger) and [`Middleware`](../kmpkt-messenger) backed by [`kmpkt-persistence`](../kmpkt-persistence).

## Scope

- `EnvelopeSerializer`: port for turning an `Envelope` into a `String` and back without coupling to a serialization library.
- `PersistentTransport`: `Transport` backed by a named `WorkQueue`, supporting `DelayStamp`, `ack`, and `reject`.
- `TransactionMiddleware`: `Middleware` that wraps each dispatched message in `Persistence.inTransaction`.
- `PersistenceWorkLock`: `WorkLock` adapter delegating to `Persistence.underLock` for `LockedWork`.
