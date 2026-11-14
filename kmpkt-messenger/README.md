# kmpkt-messenger

A message bus for Kotlin/JVM: commands and events go through a middleware pipeline to their handlers, with
optional routing to a named `Transport` instead of handling inline (`Envelope`/`Stamp`/`MessageBus`/`Transport`/`Worker`),
with no event-sourcing or DI-framework knowledge of any kind.

## Scope

- `Message`/`MessageHandler<M, R>`: a dispatchable message and the handler(s) for its type.
- `Envelope`: a message plus whatever `Stamp`s got attached to it along the way (who handled it, where it was
  routed, when it was sent, ...).
- `MessageBus`/`DefaultMessageBus`: runs an envelope through a list of `Middleware`, in order.
  `HandleMessageMiddleware` is the one that actually invokes the handler(s) - it belongs last in the chain.
  `HandlerPolicy.SINGLE` (exactly one handler, e.g. a command bus) or `MULTIPLE` (zero or more, e.g. an event
  bus) controls how many handlers a message may have.
- `Transport`/`SyncTransport`: a transport is a delivery mechanism for envelopes.
  `TransportRegistry`/`DefaultTransportRegistry` statically maps a message type to a transport name;
  `SendMessageMiddleware` does the routing, consulting a per-envelope `RouteStamp` override first. A message
  with no route is handled inline on the bus it was dispatched to.
  `SyncTransport` marks a message as received and hands it straight back to the bus
  instead of queueing it - which is what makes inline handling a routing *decision*, and so the only way a
  `RouteStamp` can override a routed message type back to inline: `RouteStamp.Sync`. Nothing is ever queued
  on it, so its `receive`/`ack`/`reject` throw rather than quietly doing nothing.
- `Worker`: polls a transport and dispatches what it finds back onto the bus. Concrete queued transports
  (e.g. an in-memory channel) live in their own, optional modules.

## Usage

```kotlin
class Greet : Message

class GreetHandler : MessageHandler<Greet, Unit> {
    override suspend fun invoke(message: Greet) = println("hello")
}

val registry = DefaultHandlerRegistry().apply { register(Greet::class, GreetHandler()) }
val bus =
    DefaultMessageBus(
        middleware = listOf(HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC())),
        handlerRegistry = registry,
    )

bus.dispatch(Greet())
```

DI wiring (discovering handlers, building the middleware chain, etc.) is handled by the optional `di` source set -
not needed to use this module directly.
