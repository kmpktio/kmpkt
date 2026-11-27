# kmpkt

> Note: This package is in early development

`kmpkt` is a modular framework for building CQRS, event-sourced, and message-driven applications
in Kotlin. Commands, queries, events, aggregates, policies, projections, and background workers
are wired at compile time with zero runtime reflection.

## Why KmpKt?

KmpKt combines the architecture of Symfony DependencyInjection (compiler passes, tagging, decoration) and Symfony Messenger (envelopes, stamps, buses, middleware, transports) with Kotlin's type safety, coroutines and compile-time KSP code generation. It is built for modular monoliths, from simple CQRS to full event sourcing.

See [Why KmpKt?](docs/why.md) for the full background and credits to Fabien Potencier and the Symfony team.

## Platform & Roadmap

Even though the name suggests Kotlin Multiplatform, KmpKt currently targets **JVM only**. Once the core APIs stabilize, the plan is to port the runtime modules to Kotlin Multiplatform so the Messenger and Outbox Relay can also power reliable mobile-to-backend communication.

## Gradle Setup

`kmpkt` is currently published to GitHub Packages. Add the repository to
`settings.gradle.kts` and apply `io.kmpkt.di` in `build.gradle.kts`:

```kotlin
// settings.gradle.kts
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

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.3.10"
    id("io.kmpkt.di") version "0.1.0"
}

val kmpktVersion = "0.1.0"

dependencies {
    // Core DI + messaging + CQRS buses
    implementation("io.kmpkt:kmpkt-di:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-command-bus:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-query-bus:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-event-bus:$kmpktVersion")

    // Add event sourcing, policies, and projections when needed
    implementation("io.kmpkt:kmpkt-event-store:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-policy:$kmpktVersion")
    implementation("io.kmpkt:kmpkt-projection:$kmpktVersion")
}
```

For full Gradle configuration details, see [Getting Started](docs/getting-started.md).

## Quick Glance

Define commands, queries, handlers, and an `ApplicationRunner` in `src/main/kotlin`, configure
your application in `src/di/kotlin`, and run it with `KmpKt.run<TodoApp>(args)` — no
annotations required:

```kotlin
object TodoApp : KmpKtApplication

data class AddTodo(val id: String, val title: String) : Command

class AddTodoHandler(
    private val todos: TodoRepository,
) : CommandHandler<AddTodo> {
    override suspend fun handle(command: AddTodo) {
        todos.save(Todo(command.id, command.title))
    }
}

class TodoRunner(
    private val commands: CommandBus,
    private val queries: QueryBus,
) : ApplicationRunner {
    override fun run(args: Array<String>) = runBlocking {
        commands.dispatch(AddTodo(id = "1", title = "Buy milk"))
        println(queries.ask(ListTodos))
    }
}

fun main(args: Array<String>) = KmpKt.run<TodoApp>(args)
```

## Documentation

- [Documentation Index](docs/README.md)
- [Why KmpKt?](docs/why.md)
- [Getting Started with Gradle](docs/getting-started.md)
- [Module Guide](docs/modules.md)
- [CQRS & Event Sourcing Architecture](docs/cqrs-and-event-sourcing.md)

## Examples

- [`examples/todo-cqrs`](examples/todo-cqrs) — Todo list with CQRS, typed buses, and DI
  (without event sourcing)
- [`examples/todo-event-sourced`](examples/todo-event-sourced) — The same Todo list built with
  event-sourced aggregates, projections, and policies
- [`examples/modulith`](examples/modulith) — Modular monolith combining independent `todos`,
  `audit`, `telemetry`, and `features` modules via compile-time decorators and component options
- [`examples/messenger`](examples/messenger) — Multi-bus routing, custom middleware stamps,
  priority event handlers, async worker retries, and dead-lettering

## Modules

| Layer | Modules |
| --- | --- |
| **DI** | `kmpkt-di`, `kmpkt-di-compiler-api`, `kmpkt-di-compiler`, `kmpkt-di-compiler-plugin` |
| **Messaging** | `kmpkt-worker`, `kmpkt-messenger`, `kmpkt-messenger-outbox-relay`, `kmpkt-messenger-clikt`, `kmpkt-command-bus`, `kmpkt-event-bus`, `kmpkt-query-bus` |
| **Persistence** | `kmpkt-persistence`, `kmpkt-persistence-jdbc`, `kmpkt-messenger-persistence` |
| **Event Sourcing** | `kmpkt-aggregate`, `kmpkt-event-store`, `kmpkt-aggregate-snapshot`, `kmpkt-serialization-kotlinx` |
| **Read & React** | `kmpkt-policy`, `kmpkt-projection` |
| **Resilience** | `kmpkt-resilience` |
| **Bridges** | `kmpkt-clikt-bridge`, `kmpkt-ktor-bridge` |

See the [Module Guide](docs/modules.md) for details on each module.

## Build & Verify

```bash
./gradlew check
```

Run mutation tests:

```bash
./gradlew pitest
```
