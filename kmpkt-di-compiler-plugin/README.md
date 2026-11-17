# kmpkt-di-compiler-plugin

Gradle plugin (`KmpktDiPlugin`) that configures KSP and `di` source sets for projects using [`kmpkt-di`](../kmpkt-di).

## Scope

- `KmpktDiPlugin`: applies `com.google.devtools.ksp`, creates and wires the `di` source set (`src/di/kotlin`, `src/di/resources`) for Kotlin JVM and Kotlin Multiplatform projects, propagates `-Xexplicit-api=strict` to the `di` compilation when enabled on the project, and exposes `diElements` artifacts across project dependencies.
- `KmpktDiExtension` (`kmpktDi` DSL): configures `groupId` (default `"io.kmpkt"`) and `version` (default `"0.1.0"`) for automatic `kmpkt-di`, `kmpkt-di-compiler`, and `kmpkt-di-compiler-api` dependencies.

## Mutation Testing

Pitest is disabled for this module: `KmpktDiPluginTest` verifies the plugin via Gradle TestKit (`GradleRunner`), which executes in a separate Gradle daemon process outside Pitest's instrumented classloader, leaving TestKit-covered code reported as `NO_COVERAGE`.
