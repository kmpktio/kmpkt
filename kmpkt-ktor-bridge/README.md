# kmpkt-ktor-bridge

Ktor Netty server integration for KmpKt compile-time dependency injection. Collects registered `HttpRoutes`, `KtorServerPlugin`, and `Worker` services into a `KtorServer` service.

## Building Blocks

### Runtime

| Symbol | Description |
| --- | --- |
| `KtorServer` | Embeds Netty via `embeddedServer`, installs `KtorServerPlugin` and `HttpRoutes` contributions, and binds `ManagedWorker` and `UnmanagedWorker` lifecycles to `ApplicationStarted` and `ApplicationStopping`. |
| `KtorServerConfig` | Server configuration data class holding the HTTP `port` (default `8080`). |
| `KtorServerPlugin` | Functional interface for installing application-level Ktor plugins and configuration. |
| `HttpRoutes` | Functional interface for contributing HTTP routes to Ktor's `Routing`. |

### DI Module

| Symbol | Description |
| --- | --- |
| `KtorComponent` | `Component` that autoconfigures `HttpRoutes`, `Worker`, and `KtorServerPlugin` services and registers `KtorServerConfig` and `KtorServer`. |
| `KtorOptions` | Configuration options for setting the server `port` and registering `KtorServerPlugin` factory `module` functions. |
| `HttpRoutesTag`, `WorkerTag`, `KtorPluginTag` | `Tag` objects used to collect routes, workers, and plugins into `KtorServer`. |
| `ContainerBuilder.ktor` | DSL extension function for configuring `KtorComponent`. |

## Installation

Add the dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-ktor-bridge:0.1.0")
}
```

## DI Setup

`KtorComponent` is discovered automatically from `META-INF/services` when `kmpkt-ktor-bridge` is on the classpath. Enable package discovery and configure `KtorOptions` inside `Configuration<WebApp>` in `src/di/kotlin`:

```kotlin
import io.kmpkt.bridge.ktor.di.ktor
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.env

class WebConfiguration : Configuration<WebApp> {
    override fun ContainerBuilder.configure() {
        discover("com.example.web")
        ktor {
            port = env("PORT", 8080)
        }
    }
}
```

## Usage

Implement `HttpRoutes` and an `ApplicationRunner` that starts `KtorServer` in `src/main/kotlin`:

```kotlin
import io.kmpkt.bridge.ktor.routing.HttpRoutes
import io.kmpkt.bridge.ktor.server.KtorServer
import io.kmpkt.di.ApplicationRunner
import io.kmpkt.di.KmpKtApplication
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

object WebApp : KmpKtApplication

class HealthRoutes : HttpRoutes {
    override fun Routing.install() {
        get("/health") { call.respondText("ok") }
    }
}

class ServerRunner(
    private val server: KtorServer,
) : ApplicationRunner {
    override fun run(args: Array<String>) = server.start()
}
```

## Extending

### HTTP routes

Implement `HttpRoutes` and register or discover it; `KtorComponent` autoconfigures it with `HttpRoutesTag`:

```kotlin
class UserRoutes : HttpRoutes {
    override fun Routing.install() {
        get("/users") { call.respondText("[]") }
    }
}
```

### Ktor server plugins

Implement `KtorServerPlugin` or register a plugin factory function via `ktor { module(...) }` to install Ktor plugins or error handling:

```kotlin
fun jsonPlugin(): KtorServerPlugin = KtorServerPlugin {
    // install(ContentNegotiation) { ... }
}

// In Configuration.configure():
ktor {
    module(::jsonPlugin)
}
```

### Background workers

Implement `ManagedWorker` or `UnmanagedWorker` from `kmpkt-worker`; registered or discovered `Worker` services are tagged with `WorkerTag` and bound to Ktor's `ApplicationStarted` and `ApplicationStopping` events:

```kotlin
class CleanupWorker : ManagedWorker {
    override fun start() { /* start background loop */ }
    override suspend fun stop() { /* flush and shut down */ }
}
```
