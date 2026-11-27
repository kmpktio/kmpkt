# kmpkt-di

Runtime contracts for KmpKt compile-time dependency injection. Defines the application marker and startup runner interfaces used as entry points for the generated `io.kmpkt.di.KmpKt` launcher.

## Building Blocks

| Symbol | Description |
| --- | --- |
| `KmpKtApplication` | Marker interface for an application entry point wired by a `Configuration`, including `KmpKtApplication.Default` for applications without a custom marker. |
| `KmpKtContext` | Marker interface for a typed scoped child container declared via `context<M, H, I> { ... }` and extended across modules via `context<M> { ... }`. |
| `ApplicationRunner` | Startup runner interface automatically tagged with `ApplicationRunnerTag`, retained by dead-code elimination, and invoked in priority order by `KmpKt.run`. |
| `ContextHandle` | `AutoCloseable` handle representing an opened scoped child container, exposing its domain `context` and projecting onto module views via `view<V>()`. |
| `ContextScope` | Root-injected factory for opening (`open(input)`) or running (`invoke(input) { ... }`) scoped child containers. |
| `ContextFactory` | Root-registered factory contract for creating a domain context `C` from runtime input `I` and tearing it down in `destroy(context)`. |

## Installation

Apply the `io.kmpkt.di` Gradle plugin in `build.gradle.kts` for both your feature/core module and your entrypoint (`app`) module. It adds `kmpkt-di` automatically and creates the `src/di/kotlin` source set:

```kotlin
plugins {
    kotlin("jvm")
    id("io.kmpkt.di") version "0.1.0"
}
```

## Usage

Define your `KmpKtApplication` marker and `ApplicationRunner` implementations in `core/src/main/kotlin`, wire them in `core/src/di/kotlin` with `Configuration<TodoApp>`, and launch from a downstream `app` module in `app/src/main/kotlin`.

For registering services (`service`, `factory`, `external`, `discover`), decorators, tags and components inside `src/di/kotlin`, see the [`kmpkt-di-compiler-api` Container DSL](../kmpkt-di-compiler-api/README.md#container-dsl).

```kotlin
// core/src/main/kotlin/TodoRunner.kt
import io.kmpkt.di.ApplicationRunner
import io.kmpkt.di.KmpKtApplication

object TodoApp : KmpKtApplication

class TodoRunner(
    private val service: TodoService,
) : ApplicationRunner {
    override fun run(args: Array<String>) {
        service.execute(args)
    }
}
```

```kotlin
// app/src/main/kotlin/Main.kt
import io.kmpkt.di.KmpKt

fun main(args: Array<String>) = KmpKt.run<TodoApp>(args)
```

### Scoped Contexts

Use `ContextScope` in root services to open isolated child containers with runtime input (`invoke` auto-closes at the end of the block; `open` returns an `AutoCloseable` handle for long-lived or event-driven lifecycles). Any `AutoCloseable` services inside the child container are closed in reverse order before `ContextFactory.destroy(context)` runs:

```kotlin
// Short-lived scope (opens, runs, and closes automatically)
requestScope(HttpRequest("/orders")) {
    handler.handle()
}

// Long-lived scope (kept open until a disconnect/close event arrives)
val handle = storeScope.open(StoreConnection(storeId, erpEndpoint))
// ... later when the context ends:
handle.close()
```

#### Modular Contexts (`KmpKtContext` & `view<V>()`)

In a modular application, a `core` module can manage the context lifecycle using a `KmpKtContext` marker without knowing about every feature module. Feature modules contribute their own scoped services via `context<Store> { ... }`, declare a small view interface (`LoyaltyView`) containing only what they need, and project the active handle with `view<V>()`:

```kotlin
object Store : KmpKtContext

interface LoyaltyView {
    val loyalty: LoyaltyService
}

val loyalty = registry.get(storeId).view<LoyaltyView>().loyalty
```

See [examples/scoped-contexts](../examples/scoped-contexts/README.md) for a complete multi-module example.

## Extending

### Custom application marker

Implement `KmpKtApplication` to define a named application entry point (or use `KmpKtApplication.Default` for single-application modules):

```kotlin
import io.kmpkt.di.KmpKtApplication

object BillingApp : KmpKtApplication
```

### Startup runner

Implement `ApplicationRunner` on any service that should be instantiated and executed on startup by `KmpKt.run`:

```kotlin
import io.kmpkt.di.ApplicationRunner

class MigrationRunner(private val migrator: Migrator) : ApplicationRunner {
    override fun run(args: Array<String>) {
        migrator.migrate()
    }
}
```
