# kmpkt-di-compiler-plugin

Gradle plugin (`io.kmpkt.di`) that wires KSP, `src/di/kotlin` source sets, and transitive DI metadata propagation for Kotlin JVM and Kotlin Multiplatform projects.

## What It Configures

When applied, `io.kmpkt.di` automatically sets up:

- **KSP & compiler:** applies `com.google.devtools.ksp` and adds `kmpkt-di-compiler` to `ksp` and `kspDi`.
- **DI source set:** creates the `di` source set (Kotlin JVM) or `jvm` `di` compilation (Kotlin Multiplatform) at `src/di/kotlin` and `src/di/resources`, propagating `-Xexplicit-api=strict` when enabled.
- **Dependencies:** adds `kmpkt-di` and `kmpkt-di-compiler-api` to the appropriate configurations.
- **Multi-module propagation:** exposes `diJar` via `diElements` so downstream modules inherit upstream `di` metadata transitively.
- **App vs. library detection:** detects `KmpKt.run` in `main` or `commonMain` to place `diJar` on the application's `ksp` classpath, while associating `di` with `main` output in library modules.

## How to use

Apply the plugin in your module's `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
    id("io.kmpkt.di") version "0.1.0"
}
```

Optionally override the resolved `kmpkt-di` artifact coordinates via the `kmpktDi` extension:

```kotlin
kmpktDi {
    groupId.set("io.kmpkt")
    version.set("0.1.0")
}
```

For the module boundary between `core/src/di/kotlin` and `app`, see [`kmpkt-di`](../kmpkt-di/README.md). For the [Container DSL](../kmpkt-di-compiler-api/README.md#container-dsl) used inside `src/di/kotlin`, see [`kmpkt-di-compiler-api`](../kmpkt-di-compiler-api/README.md).
