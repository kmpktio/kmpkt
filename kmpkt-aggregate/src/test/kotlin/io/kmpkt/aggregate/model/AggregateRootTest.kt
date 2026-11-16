package io.kmpkt.aggregate.model

import io.kmpkt.aggregate.fixture.Counter
import io.kmpkt.aggregate.fixture.CounterIncremented
import io.kmpkt.aggregate.value.AggregateUuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
private val fixedClock: Clock = Clock.fixed(now, ZoneOffset.UTC)

class AggregateRootTest :
    FunSpec({
        test("recording an event increments the version and updates state") {
            // Arrange
            val counter = Counter.new()

            // Act
            counter.increment(fixedClock)
            counter.increment(fixedClock)

            // Assert
            counter.value shouldBe 2
            counter.version shouldBe 2
        }

        test("pulling recorded events forgets them") {
            // Arrange
            val counter = Counter.new()
            counter.increment(fixedClock)

            // Act
            val events = counter.pullRecordedEvents()

            // Assert
            events.map { it.version } shouldBe listOf(1L)
            counter.pullRecordedEvents() shouldBe emptyList()
        }

        test("persistedVersion lags behind unpersisted recorded events") {
            // Arrange
            val counter = Counter.new()

            // Act
            counter.increment(fixedClock)
            counter.increment(fixedClock)

            // Assert
            counter.persistedVersion shouldBe 0
        }

        test("replaying the same version twice is rejected") {
            // Arrange
            val id = AggregateUuid.generate<Counter>()
            val event =
                Counter
                    .new(id)
                    .apply { increment(fixedClock) }
                    .pullRecordedEvents()
                    .single()

            // Act + Assert
            shouldThrow<IllegalArgumentException> { Counter.fromHistory(listOf(event, event)) }
        }

        test("replaying an event for a different aggregate is rejected") {
            // Arrange
            val ownEvent =
                Counter
                    .new()
                    .apply { increment(fixedClock) }
                    .pullRecordedEvents()
                    .single()
            val foreignEvent =
                Counter
                    .new()
                    .apply { increment(fixedClock) }
                    .pullRecordedEvents()
                    .single()

            // Act + Assert
            shouldThrow<IllegalArgumentException> { Counter.fromHistory(listOf(ownEvent, foreignEvent)) }
        }

        test("rebuilds from history") {
            // Arrange
            val id = AggregateUuid.generate<Counter>()
            val history = Counter.new(id).apply { repeat(3) { increment(fixedClock) } }.pullRecordedEvents()

            // Act
            val counter = Counter.fromHistory(history)

            // Assert
            counter.value shouldBe 3
            counter.version shouldBe 3
        }
        test("persistedVersion is the version without what has not been saved yet") {
            // Arrange - a store compares this against the stream to spot a concurrent writer
            val counter = Counter.new(AggregateUuid.generate())

            // Act
            repeat(3) { counter.increment(fixedClock) }

            // Assert - three recorded, nothing saved
            counter.version shouldBe 3
            counter.persistedVersion shouldBe 0

            // Act - as a repository does after writing them
            counter.pullRecordedEvents()

            // Assert
            counter.persistedVersion shouldBe 3
        }

        test("an event whose version skips one is refused, naming what was expected") {
            // Arrange - a gap would mean rebuilding from a history that is missing an event
            val id = AggregateUuid.generate<Counter>()

            // Act + Assert
            val failure =
                shouldThrow<IllegalArgumentException> {
                    Counter.fromHistory(listOf(CounterIncremented(id, 2, now)))
                }
            failure.message.orEmpty().shouldContain("expected 1")
        }

        test("restore sets version and applies state from a snapshot-like event, rejecting invalid preconditions") {
            class RestorableCounter(
                id: AggregateUuid<Counter>,
            ) : BaseAggregateRoot<Counter>(id) {
                var value: Int = 0
                    private set

                fun applyRestored(event: CounterIncremented) = restore(event)

                override fun updateState(event: io.kmpkt.aggregate.event.DomainEvent<Counter>) {
                    when (event) {
                        is CounterIncremented -> value++
                    }
                }
            }

            val id = AggregateUuid.generate<Counter>()
            val otherId = AggregateUuid.generate<Counter>()
            val counter = RestorableCounter(id)

            shouldThrow<IllegalArgumentException> {
                counter.applyRestored(CounterIncremented(otherId, 1, now))
            }
            shouldThrow<IllegalArgumentException> {
                counter.applyRestored(CounterIncremented(id, 0, now))
            }

            counter.applyRestored(CounterIncremented(id, 5, now))
            counter.version shouldBe 5
            counter.value shouldBe 1

            shouldThrow<IllegalArgumentException> {
                counter.applyRestored(CounterIncremented(id, 6, now))
            }
        }
    })
