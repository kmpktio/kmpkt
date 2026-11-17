package io.kmpkt.snapshot.repository

import io.kmpkt.aggregate.exception.AggregateNotFound
import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.event.store.InMemoryEventStore
import io.kmpkt.event.store.repository.DefaultAggregateRepository
import io.kmpkt.snapshot.event.SnapshotEventCheckpointPredicate
import io.kmpkt.snapshot.fixture.Counter
import io.kmpkt.snapshot.fixture.CounterSnapshotted
import io.kmpkt.snapshot.strategy.VersionIntervalSnapshotStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun repository(
    eventStore: InMemoryEventStore = InMemoryEventStore(SnapshotEventCheckpointPredicate()),
    interval: Int = 2,
) = SnapshotAggregateRepository(
    DefaultAggregateRepository(eventStore, Counter.TYPE, Counter),
    VersionIntervalSnapshotStrategy(interval),
)

class SnapshotAggregateRepositoryTest :
    FunSpec({
        test("reloads from full history when no snapshot exists yet") {
            // Arrange
            val eventStore = InMemoryEventStore(SnapshotEventCheckpointPredicate())
            val repository = repository(eventStore, interval = 10)
            val counter = Counter.new()
            counter.increment()

            // Act
            repository.save(counter)
            val loaded = repository.get(counter.id)

            // Assert
            loaded.value shouldBe 1
            eventStore.load(Counter.TYPE, counter.id).none { it is CounterSnapshotted } shouldBe true
        }

        test("takes a snapshot event once the strategy says to, and loads from it afterwards") {
            // Arrange
            val eventStore = InMemoryEventStore(SnapshotEventCheckpointPredicate())
            val repository = repository(eventStore, interval = 2)
            val counter = Counter.new()
            counter.increment()
            counter.increment()

            // Act
            repository.save(counter)

            // Assert
            val history = eventStore.load(Counter.TYPE, counter.id)
            history.shouldHaveSize(1)
            history.single().shouldBeInstanceOf<CounterSnapshotted>().version shouldBe 3L
            eventStore.loadFrom(after = 0, limit = 10).map { it.event.version } shouldBe listOf(1L, 2L)
        }

        test("loads a snapshot plus the events recorded after it") {
            // Arrange
            val repository = repository(interval = 2)
            val counter = Counter.new()
            counter.increment()
            counter.increment()
            repository.save(counter)

            val reloaded = repository.get(counter.id)
            reloaded.increment()
            repository.save(reloaded)

            // Act
            val loaded = repository.get(counter.id)

            // Assert: v1, v2, v3 (snapshot), v4, v5 (snapshot)
            loaded.value shouldBe 3
            loaded.version shouldBe 5L
        }

        test("getting an unknown aggregate throws AggregateNotFound") {
            // Arrange
            val repository = repository()

            // Act + Assert
            shouldThrow<AggregateNotFound> {
                repository.get(AggregateUuid.generate())
            }
        }
    })
