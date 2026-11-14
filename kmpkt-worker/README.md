# kmpkt-worker

The loop around something that has to keep happening: polling a transport, bringing a subscription up to date,
draining a queue. Nothing in here knows what the work is - it only knows how often to ask, what to do when it
fails, and how to be stopped.

A leaf module: it depends on coroutines and SLF4J, and on nothing else of this family. `kmpkt-messenger` and the
event-sourcing subscription side both use it, which is the reason it exists separately - the twenty lines of a
polling loop are the ones that are easy to get wrong (busy-spinning, a swallowed cancellation, an error that
loops forever), and having them twice means getting them wrong twice.

## Scope

- `Work`: one pass. Returns how many units it handled, so the worker knows whether to come straight back or
  wait. Throwing means the pass could not finish.
- `RetryStrategy`: what happens after a pass threw - `none()`, `fixed(times)`, `backOff()`, `forever()`.
  Returning `null` means give up.
- `Worker`: the loop, in two kinds that differ in **what becomes of a failure the retry strategy gave up on**.
  - `ManagedWorker` keeps it in: reports it to `onGiveUp` and stops only itself, so one stuck subscription
    cannot take the application or the other workers with it. Owns its coroutine - nothing of that machinery
    is visible from outside - and has `start()` / `stop(timeout)`.
  - `UnmanagedWorker` lets it out. For a process whose only job this is, where dying with the exception and a
    non-zero exit code is right, and swallowing it would leave something that looks alive and does nothing.

## Usage

```kotlin
// in the background of an application, next to others
val worker = ManagedWorker("async-transport", TransportWork(transport, "async", bus, logger), logger)
worker.start()
// on shutdown
worker.stop(timeout = 10.seconds)

// or as a CLI command, in the foreground, until killed
runBlocking { UnmanagedWorker("projections", work, logger).run() }
```

Those two entry points are the point: the same `Work` runs as a background task inside an application or as
its own process, without being written twice.

`stop()` asks the loop to finish its current pass before stopping, and cancels it where it stands if that takes
longer than the timeout. The graceful part is the attempt, not a promise: a pass is where a subscriber is
halfway through something - an event handled but its position not yet saved, an HTTP call in flight - and
cutting that short is safe but wasteful, since the work is simply done again. Which is why subscribers have to
be idempotent either way.

## Failure

A pass that handled something resets the attempt count, so an occasional hiccup never accumulates towards
giving up. When the strategy does give up, the worker **stops and rethrows** rather than carrying on - a
subscription stuck on an event it cannot handle will not un-stick itself, and a worker that retries forever
turns a loud failure into log lines nobody reads.

Failures a pass knows how to handle belong inside the pass. `TransportWork` rejects a single bad message and
carries on with the batch, and only lets a failure of the transport itself reach the worker.

## `ApplicationRunner` vs. `Worker`

`WorkerComponent` turns every registered `Work` into a `Worker` (`ManagedWorker`), but does **not**
start it automatically during `KmpKt.run()`:

- **`ApplicationRunner`** starts the **process** automatically (`KmpKt.run<App>(args)`).
- **`Worker`** is a **background loop** whose lifecycle is owned by a host:
  - [`kmpkt-ktor-bridge`](../kmpkt-ktor-bridge): `KtorServer` automatically starts and stops all
    `Worker` services alongside HTTP routes.
  - [`kmpkt-clikt-bridge`](../kmpkt-clikt-bridge): a dedicated `CliktCommand` injects a `Worker`
    and runs it only when that subcommand is invoked.
  - **Standalone:** a startup `ApplicationRunner` calls `start()` (see
    [`examples/messenger`](../examples/messenger)).
