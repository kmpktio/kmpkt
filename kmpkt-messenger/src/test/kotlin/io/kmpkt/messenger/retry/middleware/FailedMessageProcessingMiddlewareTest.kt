package io.kmpkt.messenger.retry.middleware

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.stamp.SentToFailureTransportStamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FailedMessageProcessingMiddlewareTest :
    FunSpec({
        test("restores original ReceivedStamp when consumed from failure transport") {
            val middleware = FailedMessageProcessingMiddleware("dead-letter")
            val envelope =
                Envelope(Greet("Milch"))
                    .with(SentToFailureTransportStamp("async-jobs", "IllegalStateException", "boom"))
                    .with(ReceivedStamp("dead-letter"))
            var forwarded: Envelope? = null

            middleware.handle(envelope) {
                forwarded = it
                it
            }

            forwarded?.last<ReceivedStamp>() shouldBe ReceivedStamp("async-jobs")
        }

        test("passes through untouched when not from a failure transport or without failure stamp") {
            val middleware = FailedMessageProcessingMiddleware("dead-letter")

            val normal = Envelope(Greet("Milch")).with(ReceivedStamp("async-jobs"))
            middleware.handle(normal) { it } shouldBe normal

            val withoutStamp = Envelope(Greet("Milch")).with(ReceivedStamp("dead-letter"))
            middleware.handle(withoutStamp) { it } shouldBe withoutStamp
        }
    })
