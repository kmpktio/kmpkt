# kmpkt-serialization-kotlinx

kotlinx.serialization adapter for [`kmpkt-event-store`](../kmpkt-event-store)'s `EventSerializer`.
Optional: the port lives in `kmpkt-event-store`, so an application can implement it with anything
else instead.

## Usage

Group the aggregate's events under one `@Serializable sealed` parent and hand its serializer over - the
compiler plugin generates the polymorphic dispatch, so there is no registry to keep in sync and a forgotten
event type cannot happen:

```kotlin
@Serializable
sealed interface ListEvent : DomainEvent<ManagedList>

@Serializable
@SerialName("ItemAdded")
data class ItemAdded(...) : ListEvent

val eventSerializer = KotlinxEventSerializer(ListEvent.serializer())
```

Defaults to `persistenceJson` (discriminator `type`, unknown keys ignored so a record written by an older
schema still reads). Pass your own `Json` to override it.

An application whose events share no sealed parent - several aggregates with their own hierarchies, say -
lists them instead:

```kotlin
val eventSerializer =
    KotlinxEventSerializer {
        event<ItemAdded>()
        event<CategoryAdded>()
    }
```

That hands the listing to kotlinx's own `SerializersModule`/`PolymorphicSerializer`, so the wire format is
identical to the sealed case - same `type` discriminator, from the same `@SerialName` - and you can move
between the two without touching stored data. Prefer the sealed parent where you can have one: there a
forgotten event is a compile error, here it is a failure the first time one is read back.

## Helpers

`InstantSerializer` for `java.time.Instant` as ISO-8601, and `AggregateUuidSerializer` for a UUID-backed
`AggregateId`. Reference them per property: `@Serializable(with = InstantSerializer::class)`.
