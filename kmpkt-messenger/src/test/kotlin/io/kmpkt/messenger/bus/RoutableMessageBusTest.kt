package io.kmpkt.messenger.bus

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.stamp.BusNameStamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class StubBus(
    private val name: String,
) : MessageBus {
    val dispatched = mutableListOf<Envelope>()

    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

    override suspend fun dispatch(envelope: Envelope): Envelope {
        dispatched += envelope
        return envelope.with(BusNameStamp(name))
    }
}

class RoutableMessageBusTest :
    FunSpec({
        test("routes envelope to the bus named by BusNameStamp") {
            val commands = StubBus("commands")
            val events = StubBus("events")
            val routable = RoutableMessageBus(mapOf("commands" to commands, "events" to events))

            routable.dispatch(Envelope(Greet("Alice")).with(BusNameStamp("events")))

            commands.dispatched shouldBe emptyList()
            events.dispatched.size shouldBe 1
        }

        test("uses fallback bus when envelope has no BusNameStamp") {
            val commands = StubBus("commands")
            val routable = RoutableMessageBus(mapOf("commands" to commands), fallback = commands)

            routable.dispatch(Greet("Bob"))

            commands.dispatched.size shouldBe 1
        }

        test("throws when BusNameStamp names an unknown bus or when neither stamp nor fallback exists") {
            val commands = StubBus("commands")
            val routable = RoutableMessageBus(mapOf("commands" to commands))

            shouldThrow<IllegalStateException> {
                routable.dispatch(Envelope(Greet("Alice")).with(BusNameStamp("unknown")))
            }
            shouldThrow<IllegalStateException> {
                routable.dispatch(Greet("Bob"))
            }
        }
    })
