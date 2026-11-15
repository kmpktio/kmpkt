package io.kmpkt.projection

import io.kmpkt.aggregate.value.AggregateType
import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.event.store.EventStore
import io.kmpkt.event.store.InMemoryEventStore
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.persistence.InMemoryPersistence
import io.kmpkt.projection.fixture.ItemAdded
import io.kmpkt.projection.fixture.ItemRemoved
import io.kmpkt.projection.fixture.TestAggregate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val aggregateType = AggregateType<TestAggregate>("TestAggregate")
private val aggregateId = AggregateUuid.generate<TestAggregate>()
private val now = Instant.parse("2026-01-01T00:00:00Z")

private data class NameRow(
    val name: String,
    val seen: Int = 1,
)

/** Counts by reading its own row first - the read-modify-write an event alone cannot do. */
private class WhenItemAddedCountNames : Projector<ItemAdded, NameRow> {
    override suspend fun project(
        event: ItemAdded,
        view: ProjectionView<NameRow>,
    ): List<ProjectionChange<NameRow>> {
        val existing = view.findOrNull(match(NameRow::name to event.name))

        return listOf(Put(existing?.copy(seen = existing.seen + 1) ?: NameRow(event.name)))
    }
}

private class WhenItemRemovedForgetName : Projector<ItemRemoved, NameRow> {
    override suspend fun project(
        event: ItemRemoved,
        view: ProjectionView<NameRow>,
    ): List<ProjectionChange<NameRow>> = listOf(Remove(match(NameRow::name to event.name)))
}

/** A clock the test moves by hand, so the backoff can be observed without waiting for it. */
private class MovableClock(
    private var instant: Instant = now,
) : Clock() {
    override fun instant(): Instant = instant

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advance(by: kotlin.time.Duration) {
        instant = instant.plusMillis(by.inWholeMilliseconds)
    }
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
    private val projectors: ProjectionExecutor<NameRow> =
        ProjectionExecutor<NameRow>().apply {
            register(WhenItemAddedCountNames())
            register(WhenItemRemovedForgetName())
        },
) {
    val eventStore = InMemoryEventStore()
    val persistence = InMemoryPersistence()
    val store = InMemoryProjectionStore<NameRow> { it.name }
    val clock = MovableClock()
    val transport = ProjectionTransport("names_1", persistence, eventStore, store, projectors, clock)
    private val handler = ProjectEventHandler("names_1", store, projectors)

    /** One pass, the way `TransportWork` makes it: receive, handle, then ack or reject. */
    suspend fun pump(): Int {
        val envelopes = transport.receive()

        envelopes.forEach { envelope ->
            try {
                handler(envelope.message as ProjectEvent)
                transport.ack(envelope)
            } catch (failure: IllegalStateException) {
                transport.reject(envelope)
                throw failure
            }
        }

        return envelopes.size
    }
}

class ProjectionTransportTest :
    FunSpec({
        test("builds the projection from the whole log, including what came before it existed") {
            // Arrange - unlike a policy, a projection must see history: that is what makes it complete
            val fixture = Fixture()
            fixture.eventStore.record(0, "Milch", "Brot")

            // Act
            val handled = fixture.pump()

            // Assert
            handled shouldBe 2
            fixture.store.findAll() shouldContainExactlyInAnyOrder listOf(NameRow("Milch"), NameRow("Brot"))
        }

        test("a projector reads its own rows, so it can count") {
            // Arrange
            val fixture = Fixture()
            fixture.eventStore.record(0, "Milch", "Milch", "Milch")

            // Act
            fixture.pump()

            // Assert
            fixture.store.findAll() shouldContainExactly listOf(NameRow("Milch", seen = 3))
        }

        test("runs every projector registered for an event type") {
            // Arrange
            val fixture = Fixture()
            fixture.eventStore.record(0, "Milch")
            fixture.pump()

            // Act
            fixture.eventStore.append(
                aggregateType,
                aggregateId,
                1,
                listOf(ItemRemoved(aggregateId, 2, now, "Milch")),
            )
            fixture.pump()

            // Assert
            fixture.store.findAll().shouldBeEmpty()
        }

        test("a second pass only sees what arrived since the first") {
            // Arrange
            val fixture = Fixture()
            fixture.eventStore.record(0, "Milch")
            fixture.pump()

            // Act
            fixture.eventStore.record(1, "Brot")
            val handled = fixture.pump()

            // Assert - Milch is not counted twice
            handled shouldBe 1
            fixture.store.findAll() shouldContainExactlyInAnyOrder listOf(NameRow("Milch"), NameRow("Brot"))
        }

        test("reset discards the rows and the position, so the next pass rebuilds from the first event") {
            // Arrange
            val fixture = Fixture()
            fixture.eventStore.record(0, "Milch", "Milch")
            fixture.pump()

            // Act
            fixture.transport.reset()

            // Assert - empty until it has caught up again, then identical
            fixture.store.findAll().shouldBeEmpty()
            fixture.transport.position().shouldBeNull()
            fixture.pump()
            fixture.store.findAll() shouldContainExactly listOf(NameRow("Milch", seen = 2))
        }

        test("a rebuild gives the same result as the original build, which is what makes it safe") {
            // Arrange
            val fixture = Fixture()
            fixture.eventStore.record(0, "Milch", "Brot", "Milch")
            fixture.pump()
            val before = fixture.store.findAll().sortedBy { it.name }

            // Act
            fixture.transport.reset()
            fixture.pump()

            // Assert
            fixture.store.findAll().sortedBy { it.name } shouldBe before
        }

        test("a rejected event comes back, because the position stays where it was") {
            // Arrange - a projection is reproducible, so skipping an event would leave a permanent hole
            val fixture = Fixture(failingOn("Milch"))
            fixture.eventStore.record(0, "Milch")
            shouldThrow<IllegalStateException> { fixture.pump() }

            // Act - past the backoff, so the transport offers it again
            fixture.clock.advance(2.minutes)
            val again = fixture.transport.receive()

            // Assert
            fixture.transport.position() shouldBe 0
            again.map { (it.message as ProjectEvent).event } shouldBe
                listOf(ItemAdded(aggregateId, 1, now, "Milch"))
        }

        test("a rejection backs off, so a wedged projection does not spin") {
            // Arrange
            val fixture = Fixture(failingOn("Milch"))
            fixture.eventStore.record(0, "Milch")
            shouldThrow<IllegalStateException> { fixture.pump() }

            // Act + Assert - nothing within the backoff window, something once past it
            fixture.transport.receive().shouldBeEmpty()
            fixture.clock.advance(30.seconds)
            fixture.transport.receive().shouldBeEmpty()
            fixture.clock.advance(31.seconds)
            fixture.transport.receive().shouldHaveSize(1)
        }

        test("a batch holding nothing this projection handles advances the position anyway") {
            // Arrange - otherwise a projection of one event type in fifty looks permanently behind
            val fixture = Fixture(ProjectionExecutor())
            fixture.eventStore.record(0, "Milch", "Brot")

            // Act
            val handled = fixture.pump()

            // Assert
            handled shouldBe 0
            fixture.transport.position() shouldBe 2
        }

        test("an event store is not something you send to") {
            // Arrange
            val fixture = Fixture()

            // Act + Assert
            val failure =
                shouldThrow<IllegalStateException> {
                    fixture.transport.send(Envelope(ProjectEvent("names_1", ItemAdded(aggregateId, 1, now, "Milch"))))
                }
            failure.message shouldContain "append"
        }

        test("an envelope coming back without its position fails loudly rather than wedging the projection") {
            // Arrange
            val fixture = Fixture()
            val unstamped = Envelope(ProjectEvent("names_1", ItemAdded(aggregateId, 1, now, "Milch")))

            // Act + Assert
            shouldThrow<IllegalStateException> { fixture.transport.ack(unstamped) }
            shouldThrow<IllegalStateException> { fixture.transport.reject(unstamped) }
        }

        test("a handler refuses an event addressed to another projection") {
            // Arrange - one bus may carry several projections' events
            val fixture = Fixture()
            val handler = ProjectEventHandler("names_1", fixture.store, ProjectionExecutor<NameRow>())

            // Act + Assert
            val failure =
                shouldThrow<IllegalArgumentException> {
                    handler(ProjectEvent("names_2", ItemAdded(aggregateId, 1, now, "Milch")))
                }
            failure.message shouldContain "names_2"
        }
    })

private fun failingOn(name: String): ProjectionExecutor<NameRow> =
    ProjectionExecutor<NameRow>().apply {
        register<ItemAdded>(
            object : Projector<ItemAdded, NameRow> {
                override suspend fun project(
                    event: ItemAdded,
                    view: ProjectionView<NameRow>,
                ): List<ProjectionChange<NameRow>> =
                    if (event.name == name) throw IllegalStateException("boom") else emptyList()
            },
        )
    }
