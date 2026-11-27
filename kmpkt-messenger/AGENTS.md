# kmpkt-messenger

Module-specific invariants for messaging, transport/bus refs, stamps, routing, and failure handling.

## DI References

- **No raw string references in DI DSLs:** Never pass raw `String` names to link buses or transports in DI configuration. Declaration helpers (`bus(...)`, `transport<T>(...)`) mint and return module-managed `*Ref` value classes with `internal constructor` (`BusRef`, `TransportRef`), enforcing valid names and preventing dangling references or collisions.

## Stamps

- **Keep stamps serializable:** `Stamp` implementations must only store plain serializable values (`String`, `Int`, `Long`, `Boolean`, `Instant`, `Duration`), never live service references, `Transport` instances, `Throwable` objects, or DI refs/markers.
- **Separate routing state from error diagnostics:** `SentToFailureTransportStamp` only tracks `originalTransportName` (origin & re-archival guard); exception details belong in `ErrorDetailsStamp`.

## Transport Registry & Router

- **Keep `TransportRegistry` and `TransportRouter` separate:** `TransportRegistry` resolves registered `Transport` instances by name (`get(name: String)` / `all()`). `TransportRouter` encapsulates outbound target selection (`RouteStamp` override, message route table, bus fallback).

## Loop & Failure Guards

- **Never re-route received envelopes:** `SendMessageMiddleware` must pass envelopes carrying `ReceivedStamp` straight through to the handler chain.
- **Never re-archive failed envelopes:** `TransportWork` and `SyncTransport` must not forward an envelope to a failure transport if it already carries `SentToFailureTransportStamp`.

## KMP Compatibility

- **Avoid JVM-only reflection:** Do not introduce `KClass.qualifiedName` or `java.lang.reflect` dependencies in core messaging/stamp logic; use `simpleName` (as in `ErrorDetailsStamp.from`) when recording exception names.
