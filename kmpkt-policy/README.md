# kmpkt-policy

"Given this event, then that command" - a generalized, reusable shape for reacting to domain events with
follow-up commands, independent of whatever actually dispatches them. No dependency on a message bus, a
scheduler, or anything else infrastructure-specific - that's deliberate, so this stays usable regardless of
how events reach it (a messenger bridge, a plain in-process call, a future subscription worker, ...).

## Scope

- `Policy<E>`: reacts to one event type, deciding which `Command`s (if any) should run because of it. Pure -
  a policy decides, it never dispatches.
- `Command`: a marker interface for whatever a policy decided should run. What a concrete `Command` actually
  looks like, and how it eventually gets executed, is entirely up to the consuming application.
- `PolicyExecutor`: looks up every `Policy` registered for an incoming event's type and collects the commands
  they decide on.

## Usage

```kotlin
class SortOnItemAdded : Policy<ItemAdded> {
    override fun decide(event: ItemAdded): List<Command> = listOf(SortList(event.listId))
}

val executor = PolicyExecutor().apply { register(SortOnItemAdded()) }
val commands = executor.commandsFor(someEvent) // dispatch each one however your app does that
```

`register` is a `reified` overload - it infers which event type a `Policy` handles from `decide`'s own
signature, nothing to declare separately.
