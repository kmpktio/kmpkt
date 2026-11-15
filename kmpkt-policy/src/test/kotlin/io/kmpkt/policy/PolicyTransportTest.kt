package io.kmpkt.policy

import io.kmpkt.aggregate.value.AggregateType
import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.command.Command
import io.kmpkt.command.CommandBus
import io.kmpkt.event.store.EventStore
import io.kmpkt.event.store.InMemoryEventStore
import io.kmpkt.event.store.stamp.EventPositionStamp
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.persistence.InMemoryPersistence
import io.kmpkt.policy.fixture.ItemAdded
import io.kmpkt.policy.fixture.TestAggregate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

private val aggregateType = AggregateType<TestAggregate>("TestAggregate")
private val aggregateId = AggregateUuid.generate<TestAggregate>()
private val now = Instant.parse("2026-01-01T00:00:00Z")

private data class Sort(
    val name: String,
) : Command,
    Message

private class SortOnItemAdded : Policy<ItemAdded> {
    override fun decide(event: ItemAdded): List<Command> = listOf(Sort(event.name))
}

private class Recording : MessageBus {
    val dispatched: MutableList<Message> = mutableListOf()

    override suspend fun dispatch(message: Message): Envelope {
        dispatched += message
        return Envelope(message)
    }

    override suspend fun dispatch(envelope: Envelope): Envelope = dispatch(envelope.message)
}

private suspend fun EventStore.record(
    expectedVersion: Long,
    vararg names: String,
) = append(
    aggregateType,
    aggregateId,
    expectedVersion,
    names.mapIndexed { index, name -> ItemAdded(aggregateId, expectedVersion + index + 1, now, name) },
)

private class Fixture(
    policies: PolicyExecutor = PolicyExecutor().apply { register(SortOnItemAdded()) },
    private val failOn: String? = null,
) {
    val eventStore = InMemoryEventStore()
    val persistence = InMemoryPersistence()
    val bus = Recording()
    val transport = PolicyTransport(persistence, eventStore)
    private val handler = ExecutePolicyCommandsForEventHandler(policies, CommandBus(bus))

    val dispatched: List<Message> get() = bus.dispatched

    /** One pass, the way `TransportWork` makes it: receive, handle, then ack or reject - and carry on. */
    suspend fun pump(): Int {
        val envelopes = transport.receive()

        envelopes.forEach { envelope ->
            val event = (envelope.message as ExecutePolicyCommandsForEvent).event
            try {
                if (failOn != null && (event as ItemAdded).name == failOn) throw IllegalStateException("boom")
                handler(envelope.message as ExecutePolicyCommandsForEvent)
                transport.ack(envelope)
            } catch (_: IllegalStateException) {
                transport.reject(envelope)
            }
        }

        return envelopes.size
    }
}

class PolicyTransportTest :
    FunSpec({
        test("dispatches what the policies decided, for each event in turn") {
            // Arrange
            val fixture = Fixture()
            fixture.transport.setup()
            fixture.eventStore.record(0, "Milch", "Brot")

            // Act - one event per pass, by design
            fixture.pump() shouldBe 1
            fixture.pump() shouldBe 1

            // Assert
            fixture.dispatched shouldBe listOf(Sort("Milch"), Sort("Brot"))
        }

        test("never claims more than one event at a time") {
            // Arrange - side effects leave the system in log order or not at all
            val fixture = Fixture()
            fixture.transport.setup()
            fixture.eventStore.record(0, "Milch", "Brot", "Käse")

            // Act
            val claimed = fixture.transport.receive()

            // Assert - and nothing further while that one is unsettled
            claimed shouldHaveSize 1
            fixture.transport.receive().shouldBeEmpty()
        }

        test("starts at the head of the log, so history is not replayed") {
            // Arrange - nobody wants a thousand welcome emails a second time
            val fixture = Fixture()
            fixture.eventStore.record(0, "Milch", "Brot")

            // Act
            fixture.transport.setup()
            val handled = fixture.pump()

            // Assert
            handled shouldBe 0
            fixture.dispatched.shouldBeEmpty()
        }

        test("without setup it would skip the events that prompted the first pass") {
            // Arrange - which is why setup is separate and called at startup
            val fixture = Fixture()
            fixture.eventStore.record(0, "Milch")

            // Act - no setup, so the baseline is the beginning of the log rather than its head
            fixture.pump()

            // Assert - documents the trap rather than endorsing it
            fixture.dispatched shouldBe listOf(Sort("Milch"))
        }

        test("a failed event is recorded and left behind, so the next event still runs") {
            // Arrange - the opposite of a projection: a policy's work has already escaped
            val fixture = Fixture(failOn = "Milch")
            fixture.transport.setup()
            fixture.eventStore.record(0, "Milch", "Brot")

            // Act
            fixture.pump()
            fixture.pump()

            // Assert - Milch never ran, Brot did, and one poisoned event blocked nothing
            fixture.dispatched shouldBe listOf(Sort("Brot"))
        }

        test("a failed event is not offered again") {
            // Arrange
            val fixture = Fixture(failOn = "Milch")
            fixture.transport.setup()
            fixture.eventStore.record(0, "Milch")
            fixture.pump()

            // Act + Assert - the record is the report; retrying would repeat whatever did succeed
            fixture.transport.receive().shouldBeEmpty()
        }

        test("an event no policy reacts to is still settled, not left in flight") {
            // Arrange
            val fixture = Fixture(PolicyExecutor())
            fixture.transport.setup()
            fixture.eventStore.record(0, "Milch", "Brot")

            // Act
            fixture.pump()

            // Assert - otherwise the runner would stall on the first unrelated event
            fixture.transport.receive() shouldHaveSize 1
        }

        test("an event store is not something you send to") {
            // Arrange
            val fixture = Fixture()

            // Act + Assert
            val failure =
                shouldThrow<IllegalStateException> {
                    fixture.transport.send(Envelope(ExecutePolicyCommandsForEvent(ItemAdded(aggregateId, 1, now, "Milch"))))
                }
            failure.message shouldContain "append"
        }

        test("an envelope coming back without its position fails loudly") {
            // Arrange
            val fixture = Fixture()
            val unstamped = Envelope(ExecutePolicyCommandsForEvent(ItemAdded(aggregateId, 1, now, "Milch")))

            // Act + Assert
            shouldThrow<IllegalStateException> { fixture.transport.ack(unstamped) }
            shouldThrow<IllegalStateException> { fixture.transport.reject(unstamped) }
        }

        test("settling an event that was never claimed is a wiring mistake, not a no-op") {
            // Arrange
            val fixture = Fixture()
            fixture.transport.setup()

            // Act + Assert
            val unclaimed =
                Envelope(ExecutePolicyCommandsForEvent(ItemAdded(aggregateId, 1, now, "Milch")))
                    .with(EventPositionStamp(42))
            shouldThrow<IllegalArgumentException> { fixture.transport.ack(unclaimed) }
        }
    })
