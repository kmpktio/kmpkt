package io.kmpkt.serialization.kotlinx

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.event.store.serialization.EventSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * [EventSerializer] for an application whose events *don't* share one sealed parent - several aggregates with
 * their own hierarchies, say - by listing them instead:
 *
 * ```kotlin
 * val serializer =
 *     KotlinxEventSerializer {
 *         event<ItemAdded>()
 *         event<ItemRemoved>()
 *     }
 * ```
 *
 * The listing is handed to kotlinx's own `SerializersModule`/`PolymorphicSerializer`, so the wire format is
 * identical to the sealed case - the same `type` discriminator, from the same `@SerialName`. Prefer the sealed
 * parent where you can have one (`KotlinxEventSerializer(ListEvent.serializer())`): there the compiler knows
 * every subtype, and a forgotten event is a compile error rather than a failure the first time one is read
 * back.
 */
public fun KotlinxEventSerializer(
    json: Json = persistenceJson,
    configure: DomainEventsBuilder.() -> Unit,
): EventSerializer {
    val events = DomainEventsBuilder().apply(configure).build()

    return KotlinxEventSerializer(
        serializer = PolymorphicSerializer(DomainEvent::class),
        json = Json(json) { serializersModule = json.serializersModule + events },
    )
}

@DslMarker
public annotation class EventSerializationDsl

@EventSerializationDsl
public class DomainEventsBuilder internal constructor() {
    @PublishedApi
    internal val events: MutableMap<KClass<out DomainEvent<*>>, KSerializer<out DomainEvent<*>>> = mutableMapOf()

    /** Registers [E], under the serial name its own `@SerialName` gives it. */
    public inline fun <reified E : DomainEvent<*>> event(serializer: KSerializer<E> = serializer()) {
        events[E::class] = serializer
    }

    internal fun build(): SerializersModule {
        check(events.isNotEmpty()) { "No events registered - a KotlinxEventSerializer with nothing to read is useless" }

        return SerializersModule {
            polymorphic(DomainEvent::class) {
                events.forEach { (type, serializer) ->
                    @Suppress("UNCHECKED_CAST")
                    subclass(type as KClass<DomainEvent<*>>, serializer as KSerializer<DomainEvent<*>>)
                }
            }
        }
    }
}
