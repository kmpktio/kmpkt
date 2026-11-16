package io.kmpkt.messenger.bus.middleware

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.stamp.BusNameStamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AddBusNameStampMiddlewareTest :
    FunSpec({
        test("attaches BusNameStamp when not yet present") {
            val middleware = AddBusNameStampMiddleware("commands")
            var received: Envelope? = null

            val result =
                middleware.handle(Envelope(Greet("Milch"))) { envelope ->
                    received = envelope
                    envelope
                }

            received shouldBe Envelope(Greet("Milch")).with(BusNameStamp("commands"))
            result shouldBe received
        }

        test("preserves existing BusNameStamp") {
            val middleware = AddBusNameStampMiddleware("events")
            val original = Envelope(Greet("Milch")).with(BusNameStamp("commands"))
            var received: Envelope? = null

            middleware.handle(original) { envelope ->
                received = envelope
                envelope
            }

            received?.last<BusNameStamp>() shouldBe BusNameStamp("commands")
        }
    })
