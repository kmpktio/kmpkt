# kmpkt-di

Runtime contracts for KmpKt compile-time dependency injection. Defines the application marker and startup runner interfaces used as entry points for the generated `io.kmpkt.di.KmpKt` launcher.

## Building Blocks

| Symbol | Description |
| --- | --- |
| `KmpKtApplication` | Marker interface for an application entry point wired by a `Configuration`, including `KmpKtApplication.Default` for applications without a custom marker. |
| `ApplicationRunner` | Startup runner interface automatically tagged with `ApplicationRunnerTag`, retained by dead-code elimination, and invoked in priority order by `KmpKt.run`. |

## Installation

Apply the `io.kmpkt.di` Gradle plugin in `build.gradle.kts` for both your feature/core module and your entrypoint (`app`) module. It adds `kmpkt-di` automatically and creates the `src/di/kotlin` source set:

```kotlin
plugins {
    kotlin("jvm")
    id("io.kmpkt.di") version "0.1.0"
}
```

## Usage

Define your `KmpKtApplication` marker and `ApplicationRunner` implementations in `core/src/main/kotlin`, wire them in `core/src/di/kotlin` with `Configuration<TodoApp>`, and launch from a downstream `app` module in `app/src/main/kotlin`:

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
