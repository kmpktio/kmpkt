package io.kmpkt.aggregate.value

import io.kmpkt.aggregate.fixture.Counter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
private val namespace = Uuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

@OptIn(ExperimentalUuidApi::class)
class AggregateUuidTest :
    FunSpec({
        test("reads as the plain UUID, which is what a store writes and a URL carries") {
            // Arrange
            val uuid = Uuid.parse("74738ff5-5367-5958-9aee-98fffdcd1876")

            // Act + Assert
            AggregateUuid<Counter>(uuid).toString() shouldBe "74738ff5-5367-5958-9aee-98fffdcd1876"
        }

        test("generate gives a fresh id every time") {
            // Act + Assert
            AggregateUuid.generate<Counter>() shouldNotBe AggregateUuid.generate<Counter>()
        }

        test("fromName derives the same id from the same external id, every time") {
            // Arrange - an aggregate whose identity comes from elsewhere, e.g. an external account id
            val first = AggregateUuid.fromName<Counter>(namespace, "external-account-42")
            val second = AggregateUuid.fromName<Counter>(namespace, "external-account-42")

            // Act + Assert
            first shouldBe second
        }

        test("fromName keeps different external ids apart") {
            // Act + Assert
            AggregateUuid.fromName<Counter>(namespace, "external-account-42") shouldNotBe
                AggregateUuid.fromName<Counter>(namespace, "external-account-43")
        }
    })
