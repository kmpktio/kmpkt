package io.kmpkt.serialization.kotlinx

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.event.store.serialization.EventSerializer
import io.kmpkt.event.store.serialization.SerializedEvent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [EventSerializer] backed by kotlinx.serialization, for an application whose events share one `@Serializable`
 * `sealed` parent:
 *
 * ```kotlin
 * @Serializable
 * sealed interface ListEvent : DomainEvent<ManagedList>
 *
 * val serializer = KotlinxEventSerializer(ListEvent.serializer())
 * ```
 *
 * The compiler plugin generates the polymorphic dispatch, so there is no event registry to keep in sync and a
 * forgotten event type is impossible - adding a subtype is all it takes. An application whose events have no
 * common sealed parent needs a reflective [EventSerializer] instead.
 *
 * [SerializedEvent.type] is the class discriminator [json] is configured with (`"type"` for [persistenceJson]),
 * which is lifted out of the payload on the way out and put back on the way in, so the store's type column and
 * its payload don't say the same thing twice.
 */
public class KotlinxEventSerializer<E : DomainEvent<*>>(
    private val serializer: KSerializer<E>,
    private val json: Json = persistenceJson,
) : EventSerializer {
    private val discriminator = json.configuration.classDiscriminator

    override fun serialize(event: DomainEvent<*>): SerializedEvent {
        @Suppress("UNCHECKED_CAST")
        val encoded = json.encodeToJsonElement(serializer, event as E).jsonObject
        val type =
            encoded[discriminator]?.jsonPrimitive?.content
                ?: error(
                    "${event::class.simpleName} was serialized without a '$discriminator' discriminator - " +
                        "KotlinxEventSerializer needs a polymorphic serializer, e.g. a sealed hierarchy's own",
                )

        return SerializedEvent(
            type = type,
            payload = json.encodeToString(JsonObject.serializer(), JsonObject(encoded - discriminator)),
        )
    }

    override fun deserialize(serialized: SerializedEvent): DomainEvent<*> {
        val payload = json.parseToJsonElement(serialized.payload).jsonObject
        val withType = JsonObject(payload + (discriminator to JsonPrimitive(serialized.type)))

        return json.decodeFromJsonElement(serializer, withType)
    }
}
