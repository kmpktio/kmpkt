package io.kmpkt.serialization.kotlinx

import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateUuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * (De)serializes an [AggregateId] as its [toString]. Writing works for any implementation; reading always
 * reconstructs an [AggregateUuid] — use this serializer only where that's what the aggregate actually uses.
 * `A` is a phantom type parameter — nothing at runtime depends on it — so one no-arg instance works for every
 * aggregate type; reference it per event property with `@Serializable(with = AggregateUuidSerializer::class)`.
 */
@OptIn(ExperimentalUuidApi::class)
public class AggregateUuidSerializer<A : AggregateRoot<A>> : KSerializer<AggregateId<A>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AggregateUuid", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: AggregateId<A>,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): AggregateId<A> = AggregateUuid(Uuid.parse(decoder.decodeString()))
}
