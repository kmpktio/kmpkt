# kmpkt-projection

Read models built from a stream of events, independent of whatever actually delivers those events (a messenger
bridge, a plain in-process call, a future subscription worker, ...) and of where the read model itself lives -
no JDBC, no SQL, no particular database here.

## Scope

- `ProjectionTable`: a read-model table a `Projector` writes to (`insertIgnore`/`upsert`/`update`/`delete`/
  `truncate`) - no assumption about where or how it's actually stored. A concrete, durable implementation
  (e.g. JDBC-backed) is a separate, optional module.
- `Projector`: translates a stream of events into a `ProjectionTable` - a series of operations, keyed by the
  event type each applies to.
- `ProjectionExecutor`: runs an incoming event through every registered `Projector` that has an operation
  scheduled for its type.

## Usage

```kotlin
class ItemsProjector : Projector {
    override val tableName = "items"
    override val scheduledOperations = mapOf<KClass<out DomainEvent<*>>, suspend (DomainEvent<*>, ProjectionTable) -> Unit>(
        ItemAdded::class to { event, table -> table.upsert(mapOf("name" to (event as ItemAdded).name)) },
    )
}

val executor = ProjectionExecutor(mapOf(ItemsProjector() to someProjectionTable))
executor.project(someEvent)
```
