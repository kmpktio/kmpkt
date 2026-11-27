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

## Testing Rules

- **One behavior per test:** Keep each `test("...")` focused on a single behavior under a single condition (Arrange / Act / Assert). Treat comma-chained or method-catalog test names (e.g., `supports put, get, delete, and clear`) as a red flag and split them.
- **Separate paths, overloads, and classes:** Give happy paths, error paths (`shouldThrow`), and distinct overloads/extensions separate `test("...")` blocks, and give distinct classes under test their own `*Test.kt` file.
- **Shared test utilities:** Use `io.kmpkt.test.TestClock` (`clock.advance(duration)` / `TestClock(autoAdvance = ...)`) from the internal `:test-support` module (`testImplementation(project(":test-support"))`) instead of defining ad-hoc `Clock` subclasses or mutating raw `Instant` fields in tests.

## Documentation & Comment Rules

- **Do not enumerate what is obvious in code:** In KDoc and comments, describe the purpose concisely without listing concrete implementations, parameters, or registered items that are already visible right below (e.g., `Registers messenger CLI commands for [CliktComponent].` rather than listing every command).
- **Comment *why*, not *what*:** Avoid inline comments that restate Kotlin syntax or control flow; only add short comments for non-obvious invariants, concurrency guarantees, or ordering requirements.

## CLI Command Rules

- **Naming:** Name Clikt command classes as `VerbObjectCommand` (e.g., `ConsumeCommand`, `SetupTransportsCommand`, `ShowFailedCommand`, `RetryFailedCommand`, `RemoveFailedCommand`).
- **Validate bounds on the option itself:** Use Clikt constraints such as `.restrictTo(min = 1)` (or `min = 0` when zero is allowed) / `.check(...)` directly on numeric options instead of manual range checks in `run()`.
- **Reject conflicting target arguments:** When a command accepts positional targets alongside `--all`, reject both being passed together with `UsageError` rather than letting `--all` silently win.
- **Register in DI:** Register every new `CliktCommand` in its module's DI `SimpleComponent` (`MessengerCliktComponent`) and cover it in the component wiring test.

## DI DSL Rules

- **Prefer module-managed `*Ref` handles over raw strings:** When a DI DSL declares named resources that are referenced elsewhere in configuration (e.g., `bus("command")`, `transport<T>("outbox")`), return a module-managed `@JvmInline value class *Ref internal constructor(val name: String)` (`BusRef`, `TransportRef`) from the declaration instead of accepting raw `String` names at reference sites. This prevents typos and dangling references at compile time.

## Module & Example READMEs

- **Keep READMEs in sync:** When changing a module's public API, DI component/DSL, or behavior, update its `README.md`. Every new module must ship with a `README.md` and be listed in `docs/modules.md` (and `kmpkt-docs` sidebar).
- **Brand & prose style:** Write `KmpKt` in prose without backticks; use lowercase `kmpkt` only for package names (`io.kmpkt.*`), module slugs (`kmpkt-*`), and URLs. Keep prose readable — put code signatures in `kotlin` blocks and flows in `mermaid` blocks rather than inline prose bullets.
- **Runtime/API module structure** *(build-only modules like `kmpkt-di-compiler` / `kmpkt-di-compiler-plugin` describe what they configure/generate and link to `kmpkt-di-compiler-api`)*:
  1. `# <module>` + concise 1–2 sentence overview
  2. `## Building Blocks` — 2-column table (`Symbol` | `Description`) of **public** API/SPI only (no `Package` column, no `internal` classes)
  3. `## Installation` — `build.gradle.kts` dependency
  4. `## DI Setup` — `src/di/kotlin` configuration, if the module provides a DI `Component` or DSL
  5. `## Usage` — `src/main/kotlin` usage, kept separate from `DI Setup`
  6. `## Extending` — concrete `kotlin` examples for extension points, if any
