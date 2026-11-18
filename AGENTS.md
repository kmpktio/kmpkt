# kmpkt

`kmpkt` is a modular Kotlin framework for building CQRS, event-sourced, and message-driven applications with compile-time dependency injection (`io.kmpkt.di` / KSP, zero runtime reflection).

## Project Structure

- **Compile-time DI:** `kmpkt-di`, `kmpkt-di-compiler-api`, `kmpkt-di-compiler`, `kmpkt-di-compiler-plugin`
- **Messaging & Workers:** `kmpkt-messenger`, `kmpkt-worker`, `kmpkt-messenger-persistence`, `kmpkt-messenger-outbox-relay`, `kmpkt-messenger-clikt`
- **CQRS & Event Sourcing:** `kmpkt-command-bus`, `kmpkt-event-bus`, `kmpkt-query-bus`, `kmpkt-aggregate`, `kmpkt-event-store`, `kmpkt-aggregate-snapshot`, `kmpkt-policy`, `kmpkt-projection`
- **Persistence & Serialization:** `kmpkt-persistence`, `kmpkt-persistence-jdbc`, `kmpkt-serialization-kotlinx`
- **Bridges & Examples:** `kmpkt-clikt-bridge`, `kmpkt-ktor-bridge`, `examples/*`

## Verification

- Run module checks (tests + ktlint): `./gradlew :<module>:check`
- Run incremental mutation tests against `origin/main`: `./gradlew :<module>:pitest -Ppitest.features="+git(from[origin/main])"`

## Documentation & Comment Rules

- **Do not enumerate what is obvious in code:** In KDoc and comments, describe the purpose concisely without listing concrete implementations, parameters, or registered items that are already visible right below (e.g., `Registers messenger CLI commands for [CliktComponent].` rather than listing every command).
- **Comment *why*, not *what*:** Avoid inline comments that restate Kotlin syntax or control flow; only add short comments for non-obvious invariants, concurrency guarantees, or ordering requirements.

## CLI Command Rules

- **Naming:** Name Clikt command classes as `VerbObjectCommand` (e.g., `ConsumeCommand`, `SetupTransportsCommand`, `ShowFailedCommand`, `RetryFailedCommand`, `RemoveFailedCommand`).
- **Validate bounds on the option itself:** Use Clikt constraints such as `.restrictTo(min = 1)` (or `min = 0` when zero is allowed) / `.check(...)` directly on numeric options instead of manual range checks in `run()`.
- **Reject conflicting target arguments:** When a command accepts positional targets alongside `--all`, reject both being passed together with `UsageError` rather than letting `--all` silently win.
- **Register in DI:** Register every new `CliktCommand` in its module's DI `SimpleComponent` (`MessengerCliktComponent`) and cover it in the component wiring test.

