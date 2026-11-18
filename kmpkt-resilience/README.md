# kmpkt-resilience

Coroutine-safe resilience primitives for rate limiting and one-shot execution guards.

## Building Blocks

| Symbol | Role |
| --- | --- |
| `Fuse` | Coroutine-safe one-shot guard that runs an action at most once after succeeding, allowing retries on failure |
| `RateLimiter` | Suspending rate-limiter port supporting non-blocking and suspending permit acquisition |
| `TokenBucket` | Coroutine-safe `RateLimiter` implementing the token-bucket algorithm with configurable capacity and refill rate |

## Installation

Add the dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":kmpkt-resilience"))
}
```

## Usage

In `src/main/kotlin`, guard one-time initialization with `Fuse` and throttle operations with `TokenBucket`:

```kotlin
import io.kmpkt.resilience.Fuse
import io.kmpkt.resilience.RateLimiter
import io.kmpkt.resilience.TokenBucket
import kotlin.time.Duration.Companion.seconds

suspend fun runExample() {
    val fuse = Fuse()
    fuse.runOnce {
        // Executed at most once after succeeding
    }

    val limiter: RateLimiter = TokenBucket(
        capacity = 10,
        refillTokens = 10,
        refillPeriod = 1.seconds,
    )

    if (limiter.tryAcquire()) {
        // Non-blocking permit acquired
    }

    limiter.acquire(permits = 2) // Suspends until permits are available
}
```

## Extending

**Custom rate limiter:** Implement `RateLimiter` to back rate limiting with an alternative algorithm or a distributed store.

```kotlin
class RedisRateLimiter(private val redis: RedisClient) : RateLimiter {
    override suspend fun tryAcquire(permits: Int): Boolean =
        redis.tryConsumeTokens(permits)

    override suspend fun acquire(permits: Int) {
        while (!tryAcquire(permits)) delay(50.milliseconds)
    }
}
```
