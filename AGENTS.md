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
