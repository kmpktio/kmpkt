# kmpkt-serialization-kotlinx

A `kotlinx.serialization` adapter for KmpKt event stores, providing `EventSerializer` implementations, JSON defaults, and domain primitive serializers.

## Building Blocks

| Symbol | Role |
| --- | --- |
| `KotlinxEventSerializer` | `EventSerializer` implementation and DSL factory using `Json` and a `type` class discriminator. |
| `DomainEventsBuilder` | DSL builder for registering unsealed `DomainEvent` subtypes. |
| `persistenceJson` & `createPersistenceJson` | Preconfigured `Json` instance with `type` discriminator, defaults encoded, and unknown keys ignored. |
| `InstantSerializer` | `KSerializer` encoding and decoding `java.time.Instant` as ISO-8601 strings. |
| `AggregateUuidSerializer` | `KSerializer` encoding and decoding `AggregateId` values via UUID strings. |
| `KotlinxSerializationComponent` | DI component providing `Json` via `createPersistenceJson`. |

## Installation

Add the dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-serialization-kotlinx:0.1.0")
}
```

## DI Setup

Register `KotlinxSerializationComponent` in `src/di/kotlin` to provide the default `Json` instance via `createPersistenceJson`:

```kotlin
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.serialization.kotlinx.di.KotlinxSerializationComponent

class AppConfiguration : Configuration<App> {
    override fun ContainerBuilder.configure() {
        use<KotlinxSerializationComponent>()
    }
}
```

## Usage

In `src/main/kotlin`, use `KotlinxEventSerializer` with a `@Serializable sealed` event hierarchy (preferred for compiler-checked exhaustiveness):

```kotlin
import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.serialization.kotlinx.InstantSerializer
import io.kmpkt.serialization.kotlinx.KotlinxEventSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
sealed interface OrderEvent : DomainEvent<Order>

@Serializable
@SerialName("OrderCreated")
data class OrderCreated(
    @Serializable(with = InstantSerializer::class)
    val occurredAt: Instant,
) : OrderEvent

val eventSerializer = KotlinxEventSerializer(OrderEvent.serializer())
```

For unsealed or multiple event hierarchies, register subtypes via `DomainEventsBuilder`:

```kotlin
import io.kmpkt.serialization.kotlinx.KotlinxEventSerializer

val eventSerializer =
    KotlinxEventSerializer {
        event<OrderCreated>()
        event<InvoiceIssued>()
    }
```

## Extending

Pass a custom `Json` instance to `KotlinxEventSerializer`, keeping `classDiscriminator` set so polymorphic serialization emits the type field:

```kotlin
val customJson = Json {
    classDiscriminator = "type"
    prettyPrint = true
    ignoreUnknownKeys = true
}
val eventSerializer = KotlinxEventSerializer(OrderEvent.serializer(), customJson)
```

Supply a custom `KSerializer` for an individual event inside `DomainEventsBuilder` when it needs non-default wire formatting:

```kotlin
val eventSerializer =
    KotlinxEventSerializer {
        event<OrderCreated>(LegacyOrderCreatedSerializer)
    }
```

Implement `EventSerializer` directly if you prefer a serialization library other than `kotlinx.serialization`:

```kotlin
class JacksonEventSerializer(private val mapper: ObjectMapper) : EventSerializer {
    override fun serialize(event: DomainEvent<*>): SerializedEvent = TODO()
    override fun deserialize(serialized: SerializedEvent): DomainEvent<*> = TODO()
}
```
