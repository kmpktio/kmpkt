package io.kmpkt.event.store

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.exception.ConcurrencyConflict
import io.kmpkt.aggregate.fixture.Counter
import io.kmpkt.aggregate.fixture.CounterIncremented
import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.event.store.serialization.EventSerializer
import io.kmpkt.event.store.serialization.SerializedEvent
import io.kmpkt.persistence.InMemoryPersistence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

private class InMemoryCounterSerializer : EventSerializer {
    private val byKey = mutableMapOf<String, DomainEvent<*>>()

    override fun serialize(event: DomainEvent<*>): SerializedEvent {
        val e = event as CounterIncremented
        val payload = "${e.aggregate}|${e.version}"
        byKey[payload] = e
        return SerializedEvent("CounterIncremented", payload)
    }

    override fun deserialize(serialized: SerializedEvent): DomainEvent<*> = byKey.getValue(serialized.payload)
}

class PersistentEventStoreTest :
    FunSpec({
        test("appends, loads streams with fromVersion and limit, and ignores empty appends") {
            // Arrange
            val store = PersistentEventStore(InMemoryPersistence(), InMemoryCounterSerializer())
            val id = AggregateUuid.generate<Counter>()
            val events = Counter.new(id).apply { repeat(4) { increment(fixedClock) } }.pullRecordedEvents()

            // Act
            store.append(Counter.TYPE, id, 0, emptyList())
            store.latestPosition() shouldBe 0L

            store.append(Counter.TYPE, id, 0, events)

            // Assert
            store.load(Counter.TYPE, id) shouldContainExactly events
            store.load(Counter.TYPE, id, fromVersion = 1, limit = 2).map { it.version } shouldContainExactly listOf(2L, 3L)
            store.loadFrom(after = 1, limit = 2).map { it.position to it.event.version } shouldContainExactly
                listOf(2L to 2L, 3L to 3L)
            store.latestPosition() shouldBe 4L
        }

        test("translates LogVersionConflictException into ConcurrencyConflict") {
            // Arrange
            val store = PersistentEventStore(InMemoryPersistence(), InMemoryCounterSerializer())
            val id = AggregateUuid.generate<Counter>()
            val events = Counter.new(id).apply { increment(fixedClock) }.pullRecordedEvents()
            store.append(Counter.TYPE, id, 0, events)

            // Act + Assert
            shouldThrow<ConcurrencyConflict> {
                store.append(Counter.TYPE, id, expectedVersion = 0, events)
            }
        }

        test("checkpoint events truncate stream loading and are excluded from global feed") {
            // Arrange
            val persistence = InMemoryPersistence()
            val checkpointAtV2 = EventCheckpointPredicate { it.version == 2L }
            val store = PersistentEventStore(persistence, InMemoryCounterSerializer(), checkpointAtV2)
            val id = AggregateUuid.generate<Counter>()
            val events = Counter.new(id).apply { repeat(3) { increment(fixedClock) } }.pullRecordedEvents()

            // Act
            store.append(Counter.TYPE, id, 0, events)

            // Assert
            store.load(Counter.TYPE, id).map { it.version } shouldContainExactly listOf(2L, 3L)
            store.loadFrom(after = 0, limit = 10).map { it.event.version } shouldContainExactly listOf(1L, 3L)
            store.latestPosition() shouldBe 3L

            val emptyStore = PersistentEventStore(InMemoryPersistence(), InMemoryCounterSerializer())
            emptyStore.load(Counter.TYPE, id).shouldBeEmpty()
            NoOpEventCheckpointPredicate().isCheckpoint(events.first()) shouldBe false
        }
    })
