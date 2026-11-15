package io.kmpkt.policy

import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.command.Command
import io.kmpkt.policy.fixture.ItemAdded
import io.kmpkt.policy.fixture.ItemRemoved
import io.kmpkt.policy.fixture.TestAggregate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Instant

private val aggregateId = AggregateUuid.generate<TestAggregate>()
private val now = Instant.parse("2026-01-01T00:00:00Z")

private data class SendNotification(
    val message: String,
) : Command

private class NotifyOnItemAdded : Policy<ItemAdded> {
    override fun decide(event: ItemAdded): List<Command> = listOf(SendNotification("Added ${event.name}"))
}

private class LogOnItemAdded : Policy<ItemAdded> {
    override fun decide(event: ItemAdded): List<Command> = listOf(SendNotification("Logged ${event.name}"))
}

class PolicyExecutorTest :
    FunSpec({
        test("collects the commands of every policy registered for the event's type") {
            // Arrange
            val executor =
                PolicyExecutor().apply {
                    register(NotifyOnItemAdded())
                    register(LogOnItemAdded())
                }
            val event = ItemAdded(aggregateId, 1, now, "Milch")

            // Act
            val commands = executor.commandsFor(event)

            // Assert
            commands shouldBe listOf(SendNotification("Added Milch"), SendNotification("Logged Milch"))
        }

        test("ignores a policy registered for a different event type") {
            // Arrange
            val executor = PolicyExecutor().apply { register(NotifyOnItemAdded()) }
            val event = ItemRemoved(aggregateId, 1, now, "Milch")

            // Act
            val commands = executor.commandsFor(event)

            // Assert
            commands.shouldBeEmpty()
        }

        test("returns an empty list when no policy is registered at all") {
            // Arrange
            val executor = PolicyExecutor()

            // Act + Assert
            executor.commandsFor(ItemAdded(aggregateId, 1, now, "Milch")).shouldBeEmpty()
        }
    })
