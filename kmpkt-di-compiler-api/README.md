# kmpkt-di-compiler-api

Pure-Kotlin container model, component SPI, and multi-phase compilation pipeline for KmpKt compile-time dependency injection. Provides the DSL and intermediate representation shared between `src/di/kotlin` companion sources and `kmpkt-di-compiler` without depending on KSP.

## Building Blocks

### Component & Configuration SPI

| Symbol | Description |
| --- | --- |
| `Component` | Feature-module SPI declaring required components, default options, pre-configuration hooks, and service registrations. |
| `SimpleComponent` | Convenience `Component<Unit>` base interface for modules without configuration options. |
| `Configuration` | Application-level entry point for configuring component options and overriding service bindings for a `KmpKtApplication`. |
| `Tag` | Marker interface for service metadata with an integer `priority` ordered highest-first. |
| `ApplicationRunnerTag` | Built-in `Tag` applied automatically to `io.kmpkt.di.ApplicationRunner` services. |

### Container Model & DSL

| Symbol | Description |
| --- | --- |
| `ContainerBuilder`, `@ContainerDsl` | Mutable container builder and DSL scope for registering services, factories, external bindings, discovery packages, autoconfiguration rules, and passes. |
| `DefinitionConfigurator`, `DiscoveryBuilder`, `AutoconfigureBuilder`, `TaggedService`, `EntryPoint` | DSL configurators for tailoring service definitions, package scanning, autoconfiguration, tagged queries, and root entry points. |
| `ServiceId`, `ServiceKey`, `ServiceDefinition`, `TypedArgument`, `Scope`, `Decoration`, `Instantiation` | Core intermediate representation for service identities, scopes (`Shared`, `Prototype`), decorators, and instantiation strategies. |
| `Argument`, `ServiceReference`, `OnMissing`, `ConstValue` | Argument and constant value model, paired with DSL helpers `service`, `tagged`, `literal`, `env`, and `classLiteral`. |
| `TypeRef`, `TypeArgument`, `typeRef` | Name-based type representation independent of KSP and `KClass`. |
| `TypeOracle`, `ClassInfo`, `ClassTrait`, `Parameter`, `Diagnostics`, `CollectingDiagnostics` | Type-resolution and diagnostic reporting abstractions used during compilation. |

### Compiler Pipeline

| Symbol | Description |
| --- | --- |
| `BuilderPass` | Functional interface for inspecting or rewriting a `ContainerBuilder` during compilation. |
| `PassPhase` | Ordered compilation phases: `BeforeOptimization`, `Optimization`, `BeforeRemoving`, `Removing`, and `AfterRemoving`. |
| `ContainerCompiler`, `CompiledContainer`, `ResolvedEntryPoint` | Executes components in topological order, applies `Configuration`s, runs passes by phase and priority, and emits the compiled container. |

## Installation

Add the dependency in `build.gradle.kts` (the `io.kmpkt.di` Gradle plugin adds this to the `di` source set automatically):

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-di-compiler-api:0.1.0")
}
```

## Usage

Implement `Configuration<App>` in `src/di/kotlin` to discover packages and configure services using `ContainerBuilder`:

```kotlin
import io.kmpkt.di.KmpKtApplication
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.env

object App : KmpKtApplication

class AppConfiguration : Configuration<App> {
    override fun ContainerBuilder.configure() {
        discover("com.example.app")
        service<AppConfig> {
            argument("port", env("PORT", 8080))
        }
    }
}
```

## Extending

### Feature component

Implement `Component` or `SimpleComponent` in `src/di/kotlin` to contribute module services, options, and autoconfiguration rules:

```kotlin
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent

class MetricsComponent : SimpleComponent {
    override fun ContainerBuilder.register() {
        service<MetricsRegistry>()
    }
}
```

### Application configuration

Implement `Configuration` to configure components and override bindings for a `KmpKtApplication`:

```kotlin
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder

class AppConfiguration : Configuration<App> {
    override fun ContainerBuilder.configure() {
        discover("com.example.app")
    }
}
```

### Custom tag

Implement `Tag` to define custom metadata markers for `tagged` queries:

```kotlin
import io.kmpkt.di.compiler.Tag

data class StartupHookTag(override val priority: Int = 0) : Tag
```

### Compiler pass

Implement `BuilderPass` and register it with `addPass` to inspect or rewrite definitions during a specific compilation phase:

```kotlin
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.PassPhase

val auditPass = BuilderPass { container ->
    // inspect or modify container definitions
}
container.addPass(auditPass, PassPhase.BeforeOptimization, priority = 10)
```
