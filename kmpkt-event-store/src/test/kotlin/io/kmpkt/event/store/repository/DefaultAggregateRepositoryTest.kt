package io.kmpkt.event.store.repository

import io.kmpkt.aggregate.exception.AggregateNotFound
import io.kmpkt.aggregate.fixture.Counter
import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.event.store.InMemoryEventStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class DefaultAggregateRepositoryTest :
    FunSpec({
        test("saves and reloads an aggregate by replaying its full history") {
            // Arrange
            val repository = DefaultAggregateRepository(InMemoryEventStore(), Counter.TYPE, Counter)
            val counter = Counter.new()
            counter.increment()
            counter.increment()

            // Act
            repository.save(counter)
            val loaded = repository.get(counter.id)

            // Assert
            loaded.value shouldBe 2
            loaded.version shouldBe 2L
        }

        test("saving no recorded events writes nothing at all") {
            // Arrange
            val eventStore = InMemoryEventStore()
            val repository = DefaultAggregateRepository(eventStore, Counter.TYPE, Counter)
            val counter = Counter.new()

            // Act
            repository.save(counter)

            // Assert - not merely "get throws": the log stays empty, so no position was burnt either
            eventStore.latestPosition() shouldBe 0
            repository.findOrNull(counter.id).shouldBeNull()
        }

        test("getting an unknown aggregate throws AggregateNotFound") {
            // Arrange
            val repository = DefaultAggregateRepository(InMemoryEventStore(), Counter.TYPE, Counter)

            // Act + Assert
            shouldThrow<AggregateNotFound> {
                repository.get(AggregateUuid.generate())
            }
        }
    })
