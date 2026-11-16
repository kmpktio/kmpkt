# kmpkt-messenger-outbox-relay

Transactional outbox relay `Middleware` for `kmpkt-messenger`: forwards envelopes received from a durable source `Transport` (such as `PersistentTransport`) to a target `Transport` (such as a message broker).

Runs on a relay `MessageBus` behind `RetryMiddleware` and is consumed via `TransportWork` (optionally wrapped in `LockedWork` for single-active-relay ordering with failover).
