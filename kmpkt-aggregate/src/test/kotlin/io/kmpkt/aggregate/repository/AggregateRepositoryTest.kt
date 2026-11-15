package io.kmpkt.aggregate.repository

import io.kmpkt.aggregate.exception.AggregateNotFound
import io.kmpkt.aggregate.fixture.Counter
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateUuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Holds whatever it was given, so the default [AggregateRepository.get] can be tested on its own. */
private class Holding(
    private val stored: Counter? = null,
) : AggregateRepository<Counter> {
    override suspend fun save(aggregate: Counter) = Unit

    override suspend fun findOrNull(id: AggregateId<Counter>): Counter? = stored
}

class AggregateRepositoryTest :
    FunSpec({
        test("get returns what findOrNull found") {
            // Arrange
            val id = AggregateUuid.generate<Counter>()
            val counter = Counter.new(id)

            // Act + Assert
            Holding(counter).get(id) shouldBe counter
        }

        test("get throws where findOrNull answers null, naming the id") {
            // Arrange - the two differ only in how they treat absence, so the throw is the whole behaviour
            val id = AggregateUuid.generate<Counter>()

            // Act + Assert
            val failure = shouldThrow<AggregateNotFound> { Holding().get(id) }
            failure.message.shouldContain(id.toString())
        }

        test("findOrNull treats absence as an ordinary answer") {
            // Act + Assert
            Holding().findOrNull(AggregateUuid.generate<Counter>()).shouldBeNull()
        }
    })
