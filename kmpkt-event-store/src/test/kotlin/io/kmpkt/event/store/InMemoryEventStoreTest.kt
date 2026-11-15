package io.kmpkt.event.store

import io.kmpkt.aggregate.exception.ConcurrencyConflict
import io.kmpkt.aggregate.fixture.Counter
import io.kmpkt.aggregate.fixture.Gauge
import io.kmpkt.aggregate.fixture.SharedId
import io.kmpkt.aggregate.value.AggregateUuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

private fun fiveIncrements(id: io.kmpkt.aggregate.value.AggregateId<Counter>) =
    Counter.new(id).apply { repeat(5) { increment(fixedClock) } }.pullRecordedEvents()

class InMemoryEventStoreTest :
    FunSpec({
        test("appends and loads events for one aggregate") {
            // Arrange
            val store = InMemoryEventStore()
            val id = AggregateUuid.generate<Counter>()
            val events = Counter.new(id).apply { repeat(2) { increment(fixedClock) } }.pullRecordedEvents()

            // Act
            store.append(Counter.TYPE, id, 0, events)

            // Assert
            store.load(Counter.TYPE, id) shouldContainExactly events
        }

        test("loads only events after fromVersion") {
            // Arrange
            val store = InMemoryEventStore()
            val id = AggregateUuid.generate<Counter>()
            val events = Counter.new(id).apply { repeat(3) { increment(fixedClock) } }.pullRecordedEvents()
            store.append(Counter.TYPE, id, 0, events)

            // Act
            val remaining = store.load(Counter.TYPE, id, fromVersion = 1)

            // Assert
            remaining.map { it.version } shouldContainExactly listOf(2L, 3L)
        }

        test("a limit reads a slice of the stream, not the rest of it") {
            // Arrange - what a history view wants: a page, not ten thousand events
            val store = InMemoryEventStore()
            val id = AggregateUuid.generate<Counter>()
            store.append(Counter.TYPE, id, 0, fiveIncrements(id))

            // Act
            val slice = store.load(Counter.TYPE, id, fromVersion = 1, limit = 2)

            // Assert
            slice.map { it.version } shouldContainExactly listOf(2L, 3L)
        }

        test("no limit means the rest of the stream, since that is what a rebuild needs") {
            // Arrange
            val store = InMemoryEventStore()
            val id = AggregateUuid.generate<Counter>()
            store.append(Counter.TYPE, id, 0, fiveIncrements(id))

            // Act + Assert - a default limit would silently rebuild from part of the history
            store.load(Counter.TYPE, id).map { it.version } shouldContainExactly listOf(1L, 2L, 3L, 4L, 5L)
        }

        test("rejects an append at an outdated version") {
            // Arrange
            val store = InMemoryEventStore()
            val id = AggregateUuid.generate<Counter>()
            val events = Counter.new(id).apply { increment(fixedClock) }.pullRecordedEvents()
            store.append(Counter.TYPE, id, 0, events)

            // Act + Assert
            shouldThrow<ConcurrencyConflict> { store.append(Counter.TYPE, id, 0, events) }
        }

        test("loading an unknown aggregate returns an empty history") {
            // Arrange
            val store = InMemoryEventStore()

            // Act + Assert
            store.load(Counter.TYPE, AggregateUuid.generate()).shouldBeEmpty()
        }

        test("loadFrom returns every event across every aggregate instance, in append order") {
            // Arrange
            val store = InMemoryEventStore()
            val first = AggregateUuid.generate<Counter>()
            val second = AggregateUuid.generate<Counter>()
            store.append(Counter.TYPE, first, 0, Counter.new(first).apply { increment(fixedClock) }.pullRecordedEvents())
            store.append(Counter.TYPE, second, 0, Counter.new(second).apply { increment(fixedClock) }.pullRecordedEvents())

            // Act
            val stored = store.loadFrom()

            // Assert
            stored.map { it.position } shouldContainExactly listOf(1L, 2L)
            stored.map { it.event.aggregate } shouldContainExactly listOf(first, second)
        }

        test("loadFrom only returns events after the given position, up to the given limit") {
            // Arrange
            val store = InMemoryEventStore()
            val id = AggregateUuid.generate<Counter>()
            store.append(Counter.TYPE, id, 0, Counter.new(id).apply { repeat(3) { increment(fixedClock) } }.pullRecordedEvents())

            // Act
            val stored = store.loadFrom(after = 1, limit = 1)

            // Assert
            stored.map { it.position } shouldContainExactly listOf(2L)
        }

        test("interleaves the events of different aggregate types in one global order") {
            // Arrange
            val store = InMemoryEventStore()
            val counterId = AggregateUuid.generate<Counter>()
            val gaugeId = AggregateUuid.generate<Gauge>()

            val counted = Counter.new(counterId).apply { repeat(2) { increment(fixedClock) } }.pullRecordedEvents()
            val read = Gauge.new(gaugeId).apply { read(fixedClock) }.pullRecordedEvents()

            // Act - alternating writes to two aggregates
            store.append(Counter.TYPE, counterId, 0, listOf(counted[0]))
            store.append(Gauge.TYPE, gaugeId, 0, read)
            store.append(Counter.TYPE, counterId, 1, listOf(counted[1]))

            // Assert - one order over the whole log, which is what a subscriber across aggregates needs
            val log = store.loadFrom()
            log.map { it.position } shouldContainExactly listOf(1L, 2L, 3L)
            log.map { it.event.aggregate } shouldContainExactly listOf(counterId, gaugeId, counterId)
        }

        test("tells two aggregate types apart even when they share an id") {
            // Arrange
            val store = InMemoryEventStore()
            val counterId = SharedId<Counter>("shared")
            val gaugeId = SharedId<Gauge>("shared")
            val counterEvents = Counter.new(counterId).apply { increment(fixedClock) }.pullRecordedEvents()
            val gaugeEvents = Gauge.new(gaugeId).apply { read(fixedClock) }.pullRecordedEvents()

            // Act - both streams start at version 0, so a collision would be a ConcurrencyConflict
            store.append(Counter.TYPE, counterId, 0, counterEvents)
            store.append(Gauge.TYPE, gaugeId, 0, gaugeEvents)

            // Assert
            store.load(Counter.TYPE, counterId) shouldContainExactly counterEvents
            store.load(Gauge.TYPE, gaugeId) shouldContainExactly gaugeEvents
        }
        test("latestPosition is 0 for an empty log, and the newest event's position otherwise") {
            // Arrange
            val store = InMemoryEventStore()
            val id = AggregateUuid.generate<Counter>()

            // Act + Assert
            store.latestPosition() shouldBe 0
            store.append(Counter.TYPE, id, 0, Counter.new(id).apply { repeat(3) { increment(fixedClock) } }.pullRecordedEvents())
            store.latestPosition() shouldBe 3
        }
    })
