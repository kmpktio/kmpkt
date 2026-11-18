# kmpkt-clikt-bridge

Clikt CLI integration for KmpKt compile-time dependency injection. Wires `CliktRootCommand` as an `ApplicationRunner` and collects registered or autoconfigured `CliktCommand` services as subcommands.

## Building Blocks

### Runtime

| Symbol | Description |
| --- | --- |
| `CliktRootCommand` | `NoOpCliktCommand` implementing `ApplicationRunner`; attaches collected `CliktCommand` subcommands and applies an optional default command via `withDefaultCommand`. |

### DI Module

| Symbol | Description |
| --- | --- |
| `CliktComponent` | `Component` that autoconfigures `CliktCommand` services with `CliktCommandTag` and registers `CliktRootCommand` as an `ApplicationRunner`. |
| `CliktOptions` | Configuration options for setting the root command `name`, `defaultCommand`, and explicit `command` registrations. |
| `CliktCommandTag` | `Tag` applied to `CliktCommand` services collected into `CliktRootCommand`. |
| `ContainerBuilder.clikt` | DSL extension function for configuring `CliktComponent`. |

## Installation

Add the dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-clikt-bridge:0.1.0")
}
```

## DI Setup

`CliktComponent` is discovered automatically from `META-INF/services` when `kmpkt-clikt-bridge` is on the classpath. Configure `CliktOptions` inside `Configuration<CliApp>` in `src/di/kotlin` using `clikt { ... }` (or `configure<CliktComponent, _> { ... }`):

```kotlin
import io.kmpkt.bridge.clikt.di.clikt
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder

class CliConfiguration : Configuration<CliApp> {
    override fun ContainerBuilder.configure() {
        clikt {
            name = "my-app"
            defaultCommand = "greet"
            command<GreetCommand>()
        }
    }
}
```

## Usage

Define your `KmpKtApplication` marker and `CliktCommand` subclasses in `src/main/kotlin`:

```kotlin
import com.github.ajalt.clikt.core.CliktCommand
import io.kmpkt.di.KmpKtApplication

object CliApp : KmpKtApplication

class GreetCommand : CliktCommand(name = "greet") {
    override fun run() {
        echo("Hello")
    }
}
```

## Extending

### Adding a subcommand

Subclass `CliktCommand` and register it via `clikt`, `service`, or `discover`; `CliktComponent` autoconfigures it with `CliktCommandTag`:

```kotlin
class StatusCommand : CliktCommand(name = "status") {
    override fun run() = echo("OK")
}

// In Configuration.configure():
clikt {
    command<StatusCommand>()
}
```

### Manual subcommand tagging

Tag custom `CliktCommand` definitions with `CliktCommandTag` when registering them manually with `autoconfigured(false)`:

```kotlin
service<CustomCommand> {
    autoconfigured(false)
    tag(CliktCommandTag)
}
```
