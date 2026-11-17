package io.kmpkt.messenger.outbox.relay

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private data class SampleOutboxEvent(val id: String) : Message

class OutboxRelayMiddlewareTest :
    FunSpec({
        test("forwards envelopes received from sourceName to target without invoking downstream middleware") {
            val target = InMemoryTransport()
            val middleware = OutboxRelayMiddleware(sourceName = "outbox", target = target)
            val envelope = Envelope(SampleOutboxEvent("1")).with(ReceivedStamp("outbox"))
            var downstreamCalled = false

            val result =
                middleware.handle(envelope) {
                    downstreamCalled = true
                    it
                }

            result shouldBe envelope
            downstreamCalled shouldBe false
            target.pending() shouldBe listOf(envelope)
        }

        test("passes through envelopes without ReceivedStamp or from a different transport") {
            val target = InMemoryTransport()
            val middleware = OutboxRelayMiddleware(sourceName = "outbox", target = target)

            val plain = Envelope(SampleOutboxEvent("2"))
            var calls = 0
            middleware.handle(plain) {
                calls++
                it
            } shouldBe plain

            val other = Envelope(SampleOutboxEvent("3")).with(ReceivedStamp("other"))
            middleware.handle(other) {
                calls++
                it
            } shouldBe other

            calls shouldBe 2
            target.pending() shouldBe emptyList()
        }
    })
