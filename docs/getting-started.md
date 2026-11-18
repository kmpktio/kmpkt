# Getting Started

> **Active Development:** KmpKt is in active early development (pre-1.0); APIs, module boundaries, and configuration DSLs may still change between releases.

This guide shows how to add KmpKt to a Kotlin/JVM Gradle project.

## 1. Configure Repositories

KmpKt is published to GitHub Packages. In `settings.gradle.kts`, add the GitHub Packages
repository to both `pluginManagement` and `dependencyResolutionManagement` (using `gpr.user`
and `gpr.key` in `~/.gradle/gradle.properties`, or `GITHUB_ACTOR` / `GITHUB_TOKEN`):

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/kmpktio/kmpkt")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/kmpktio/kmpkt")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}
```

## 2. Apply `io.kmpkt.di` and Add Dependencies

In `build.gradle.kts`, apply the `io.kmpkt.di` Gradle plugin. It automatically adds the `di`
source set (`src/di/kotlin`), configures KSP, and generates `KmpKt.run<YourApp>(args)` from
your `Configuration<YourApp>` classes.

```kotlin
plugins {
    kotlin("jvm") version "2.3.10"
    id("io.kmpkt.di") version "0.1.0"
}

val kmpktVersion = "0.1.0"

dependencies {
    implementation("io.kmpkt:kmpkt-di:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-command-bus:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-query-bus:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-event-bus:$kmpktVersion")
}
```

If you want event sourcing, add:

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-event-store:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-policy:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-projection:$kmpktVersion")

    // Optional: kotlinx.serialization, snapshots, JDBC persistence
    implementation("io.kmpkt:kmpkt-serialization-kotlinx:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-aggregate-snapshot:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-persistence-jdbc:$kmpktVersion")
}
```

## 3. Declare Your App, Configuration, and Entry Point

Define a `KmpKtApplication` marker and an `ApplicationRunner` in `core/src/main/kotlin`:

```kotlin
// core/src/main/kotlin/com/example/todo/TodoCore.kt
object TodoApp : KmpKtApplication

class TodoRunner(
    private val commands: CommandBus,
    private val queries: QueryBus,
) : ApplicationRunner {
    override fun run(args: Array<String>) = runBlocking {
        commands.dispatch(AddTodo(id = "1", title = "Buy milk"))
        println(queries.ask(ListTodos))
    }
}
```

Keep your `Configuration<TodoApp>` in `core/src/di/kotlin` (separated from `app`, which calls
`KmpKt.run<TodoApp>(args)`, so `di` can reference your domain types without a Gradle cycle):

```kotlin
// core/src/di/kotlin/com/example/todo/di/TodoConfiguration.kt
class TodoConfiguration : Configuration<TodoApp> {
    override fun ContainerBuilder.configure() {
        service<InMemoryTodoRepository> { bind<TodoRepository>() }
        discover("com.example.todo") {
            services<CommandHandler<*>>()
            services<QueryHandler<*, *>>()
            services<EventHandler<*>>()
        }
    }
}
```

In `app/src/main/kotlin`, launch the application explicitly with `KmpKt.run<TodoApp>(args)`:

```kotlin
// app/src/main/kotlin/com/example/todo/Main.kt
fun main(args: Array<String>) = KmpKt.run<TodoApp>(args)
```

Because `io.kmpkt.di` resolves `di` variants transitively across project dependencies, `app` only
needs to depend on `core` (or a top-level `runtime` module) — it does not need to repeat every
`kmpkt-*` or feature module dependency:

```kotlin
// app/build.gradle.kts
plugins {
    application
    kotlin("jvm")
    id("io.kmpkt.di")
}

dependencies {
    implementation(project(":core"))
}
```

For concrete runnable projects, check [`examples/todo-cqrs`](../examples/todo-cqrs),
[`examples/todo-event-sourced`](../examples/todo-event-sourced),
[`examples/messenger`](../examples/messenger), and
[`examples/modulith`](../examples/modulith).

## `ApplicationRunner` vs. `Worker`

- **`ApplicationRunner`** is the **process entry point** and runs **automatically** when
  `KmpKt.run<YourApp>(args)` is called (ordered by `ApplicationRunnerTag(priority = ...)`). Use it
  for what starts the process itself: an HTTP server (`KtorServer`), a CLI (`CliktRootCommand`),
  or one-off setup/dispatch runners.
- **`Worker`** (`kmpkt-worker`) is a **managed background loop** (`Work` + `RetryStrategy` +
  `start()`/`stop()`). `WorkerComponent` wires every `Work` into a `Worker`, **without** starting
  it automatically. A host controls its lifecycle:
  - **Ktor (`kmpkt-ktor-bridge`):** `KtorComponent` collects all `Worker` beans and starts/stops
    them automatically with `KtorServer`.
  - **Clikt (`kmpkt-clikt-bridge`):** inject a `Worker` into a dedicated `CliktCommand` (e.g.
    `worker consume`) so CLI commands that only dispatch messages do not spin up background workers.
  - **Standalone / tests:** start and stop the `Worker` from a dedicated `ApplicationRunner`
    (see [`examples/messenger`](../examples/messenger)).
