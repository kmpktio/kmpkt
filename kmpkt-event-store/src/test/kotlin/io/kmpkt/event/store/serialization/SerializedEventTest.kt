package io.kmpkt.event.store.serialization

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SerializedEventTest :
    FunSpec({
        test("keeps the type apart from the payload, so a store can index one and blob the other") {
            // Arrange
            val serialized = SerializedEvent(type = "CounterIncremented", payload = """{"version":1}""")

            // Act + Assert
            serialized.type shouldBe "CounterIncremented"
            serialized.payload shouldBe """{"version":1}"""
        }
    })
