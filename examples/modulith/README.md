# Modulith Todo Example

Shows how `kmpkt-di` lets independent modules add cross-cutting behavior (auditing,
telemetry, feature flags) around a core domain without coupling them together.

## Domain

A todo list (`todos`) wrapped with cross-cutting audit logging (`audit`), execution telemetry
(`telemetry`), and configurable title prefixing (`features`) across separate Gradle subprojects.

## Why a modulith?

In this example, `todos` owns the core domain and knows nothing about auditing, tracing, or
feature flags. Each cross-cutting module lives in its own Gradle subproject, declares its own
DI component, and wraps `TodoRepository` at compile time.

```text
PrefixCompletedTodoRepository   (features,  priority = 30)
  -> TracingTodoRepository      (telemetry, priority = 20)
    -> AuditingTodoRepository   (audit,     priority = 10)
      -> InMemoryTodoRepository (todos)
```

## Key DI patterns shown

**Zero-config vs. configurable components.** `TodosComponent` and `AuditComponent` implement
`SimpleComponent` because they need no options. `TelemetryComponent` and
`FeatureFlagsComponent` implement `Component<Options>` and expose a small Kotlin DSL
(`telemetry { ... }` and `featureFlags { ... }`).

**Compile-time decorators.** Each cross-cutting module wraps `TodoRepository` using
`decorates<TodoRepository>(priority = ...)`. Higher priority runs on the outside.

**Conditional registration and options.** `FeatureFlagsComponent` only registers its decorator
when a prefix is configured, and `TelemetryComponent` injects `serviceName` as a compile-time
literal.

**Clean app configuration.** [ModulithConfiguration](runtime/src/di/kotlin/io/kmpkt/example/modulith/di/ModulithConfiguration.kt)
only configures high-level options:

```kotlin
class ModulithConfiguration : Configuration<ModulithApp> {
    override fun ContainerBuilder.configure() {
        telemetry {
            serviceName = "todo-modulith"
        }
        featureFlags {
            completedPrefix = "[DONE] "
        }
    }
}
```

## Modules

| Module | Role |
| --- | --- |
| `todos` | Core domain (`TodoRepository`, commands, queries, handlers) |
| `audit` | Records every save in `AuditLog` via a decorator |
| `telemetry` | Records OpenTelemetry-style spans in `SpanRecorder` |
| `features` | Prefixes completed todo titles when the feature flag is enabled |
| `runtime` | Defines `ModulithApp`, [`DemoInteractionRunner`](runtime/src/main/kotlin/io/kmpkt/example/modulith/inbound/runner/DemoInteractionRunner.kt) (simulated client entry point), and `ModulithConfiguration` |
| `app` | [Main.kt](app/src/main/kotlin/io/kmpkt/example/modulith/Main.kt) and [ModulithAppTest.kt](app/src/test/kotlin/io/kmpkt/example/modulith/ModulithAppTest.kt) |

## Run it

```bash
./gradlew :examples:modulith:app:test
```
