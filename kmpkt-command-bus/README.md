# kmpkt-command-bus

`Command`, its handler, and the bus that gets one to the other. Separate from
[`kmpkt-policy`](../kmpkt-policy) because an application may well want commands and handlers
without policies - commands arriving from HTTP, say - while every policy needs commands. The dependency only
runs one way: `policy` → `command`.

## Scope

- `Command`: the marker for an intent to change something. It is a
  [`kmpkt-messenger`](../kmpkt-messenger) `Message`, which is what lets it travel over a bus.
- `CommandHandler<C>`: carries one out. Contravariant, so a handler written against a supertype serves its
  subtypes. It is the `MessageHandler<C, Unit>` for its command, so a bus takes it as it is.
- `CommandBus`: puts a command on a `MessageBus`, so it gets that bus's middleware, routing and retries. Thin
  on purpose - it exists so an application states *what* it dispatches rather than *how*, and so a policy can
  be handed something narrower than a whole message bus.

## Usage

```kotlin
data class SortList(val listId: ListId) : Command

class SortListHandler(...) : CommandHandler<SortList> {
    override suspend fun handle(command: SortList) { ... }
}

// Registered via CommandComponent + discover(...) in Configuration
CommandBus(messageBus).dispatch(SortList(listId))
```

## At-least-once

The bus promises no exactly-once. A crash partway through a batch, or a retry after a failure, replays what
already ran, so **handlers have to be idempotent**. That isn't fixable here: only an application that knows
its own storage can make handling and progress-tracking commit together.
