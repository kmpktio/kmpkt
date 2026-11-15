package io.kmpkt.serialization.kotlinx

import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.event.store.serialization.SerializedEvent
import io.kmpkt.serialization.kotlinx.fixture.Counter
import io.kmpkt.serialization.kotlinx.fixture.CounterEvent
import io.kmpkt.serialization.kotlinx.fixture.CounterIncremented
import io.kmpkt.serialization.kotlinx.fixture.CounterReset
import io.kmpkt.serialization.kotlinx.fixture.Unrelated
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import java.time.Instant

private val aggregateId = AggregateUuid.generate<Counter>()
private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")
private val incremented = CounterIncremented(aggregateId, version = 1, occurredAt = occurredAt)

class KotlinxEventSerializerTest :
    FunSpec({
        test("round-trips an event unchanged") {
            // Arrange
            val serializer = KotlinxEventSerializer(CounterEvent.serializer())

            // Act
            val decoded = serializer.deserialize(serializer.serialize(incremented))

            // Assert
            decoded shouldBe incremented
        }

        test("names the type outside the payload, so a store can index it") {
            // Arrange
            val serializer = KotlinxEventSerializer(CounterEvent.serializer())

            // Act
            val serialized = serializer.serialize(incremented)

            // Assert
            serialized.type shouldBe "CounterIncremented"
            serialized.payload shouldNotContain "\"type\""
        }

        test("tells the subtypes of one sealed parent apart") {
            // Arrange
            val serializer = KotlinxEventSerializer(CounterEvent.serializer())
            val reset = CounterReset(aggregateId, version = 2, occurredAt = occurredAt)

            // Act
            val decoded = serializer.deserialize(serializer.serialize(reset))

            // Assert
            serializer.serialize(reset).type shouldBe "CounterReset"
            decoded shouldBe reset
        }

        test("honours a custom Json's class discriminator") {
            // Arrange
            val json = Json { classDiscriminator = "kind" }
            val serializer = KotlinxEventSerializer(CounterEvent.serializer(), json)

            // Act
            val serialized = serializer.serialize(incremented)

            // Assert
            serialized.type shouldBe "CounterIncremented"
            serialized.payload shouldNotContain "kind"
            serializer.deserialize(serialized) shouldBe incremented
        }

        test("fails loudly when handed a serializer that writes no discriminator") {
            // Arrange
            val serializer = KotlinxEventSerializer(Unrelated.serializer())
            val unrelated = Unrelated(aggregateId, version = 1, occurredAt = occurredAt)

            // Act + Assert
            shouldThrow<IllegalStateException> {
                serializer.serialize(unrelated)
            }
        }

        test("reads back a payload stored under an unknown field, so an added field is not fatal") {
            // Arrange
            val serializer = KotlinxEventSerializer(CounterEvent.serializer())
            val stored = serializer.serialize(incremented)
            val withExtra =
                SerializedEvent(
                    type = stored.type,
                    payload = stored.payload.dropLast(1) + ""","retired":true}""",
                )

            // Act + Assert
            serializer.deserialize(withExtra) shouldBe incremented
        }
    })
