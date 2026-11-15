package io.kmpkt.serialization.kotlinx

import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.serialization.kotlinx.fixture.Counter
import io.kmpkt.serialization.kotlinx.fixture.CounterEvent
import io.kmpkt.serialization.kotlinx.fixture.CounterIncremented
import io.kmpkt.serialization.kotlinx.fixture.CounterReset
import io.kmpkt.serialization.kotlinx.fixture.Unrelated
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

private val aggregateId = AggregateUuid.generate<Counter>()
private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")
private val incremented = CounterIncremented(aggregateId, version = 1, occurredAt = occurredAt)

class DomainEventsBuilderTest :
    FunSpec({
        test("round-trips an event listed in the builder") {
            // Arrange
            val serializer = KotlinxEventSerializer { event<CounterIncremented>() }

            // Act
            val decoded = serializer.deserialize(serializer.serialize(incremented))

            // Assert
            decoded shouldBe incremented
        }

        test("produces the same wire format as the sealed parent it replaces") {
            // Arrange
            val listed =
                KotlinxEventSerializer {
                    event<CounterIncremented>()
                    event<CounterReset>()
                }
            val sealed = KotlinxEventSerializer(CounterEvent.serializer())

            // Act + Assert
            listed.serialize(incremented) shouldBe sealed.serialize(incremented)
        }

        test("serializes events that share no common sealed parent, which is the whole point") {
            // Arrange
            val serializer =
                KotlinxEventSerializer {
                    event<CounterIncremented>()
                    event<Unrelated>()
                }
            val unrelated = Unrelated(aggregateId, version = 1, occurredAt = occurredAt)

            // Act + Assert
            serializer.deserialize(serializer.serialize(unrelated)) shouldBe unrelated
            serializer.deserialize(serializer.serialize(incremented)) shouldBe incremented
        }

        test("fails on an event that was never listed, rather than writing something unreadable") {
            // Arrange
            val serializer = KotlinxEventSerializer { event<CounterIncremented>() }
            val unlisted = CounterReset(aggregateId, version = 2, occurredAt = occurredAt)

            // Act + Assert
            shouldThrow<Exception> { serializer.serialize(unlisted) }
        }

        test("refuses an empty listing") {
            // Act + Assert
            shouldThrow<IllegalStateException> { KotlinxEventSerializer { } }
        }
    })
