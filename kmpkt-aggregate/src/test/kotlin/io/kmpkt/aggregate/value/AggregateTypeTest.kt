package io.kmpkt.aggregate.value

import io.kmpkt.aggregate.fixture.Counter
import io.kmpkt.aggregate.fixture.Gauge
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class AggregateTypeTest :
    FunSpec({
        test("of names the type after the class, so it is not spelled out twice") {
            // Act + Assert
            AggregateType.of<Counter>().name shouldBe "io.kmpkt.aggregate.fixture.Counter"
            AggregateType.of<Gauge>().name shouldBe "io.kmpkt.aggregate.fixture.Gauge"
        }

        test("the name is qualified, so two aggregates of the same simple name cannot collide") {
            // Act + Assert - a collision would mix two aggregates' streams with nothing to notice it
            AggregateType.of<Counter>().name shouldEndWith ".Counter"
            AggregateType.of<Counter>().name shouldBe "io.kmpkt.aggregate.fixture.Counter"
        }

        test("the name can still be set by hand, which is how a moved aggregate keeps its streams") {
            // Act + Assert - the name is persisted in every event, so it outlives the class's package
            AggregateType<Counter>("old.pkg.Counter").name shouldBe "old.pkg.Counter"
        }

        test("reads as its plain name, so it can be interpolated into stream keys and messages") {
            // Act + Assert
            "${AggregateType.of<Counter>()}" shouldBe "io.kmpkt.aggregate.fixture.Counter"
        }
    })
