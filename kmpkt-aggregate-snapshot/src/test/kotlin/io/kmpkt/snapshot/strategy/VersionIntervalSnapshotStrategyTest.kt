package io.kmpkt.snapshot.strategy

import io.kmpkt.snapshot.fixture.Counter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val everyTen = VersionIntervalSnapshotStrategy(interval = 10)

private fun crossing(
    previous: Long,
    new: Long,
) = everyTen.shouldSnapshot(previousVersion = previous, newVersion = new)

class VersionIntervalSnapshotStrategyTest :
    FunSpec({
        test("snapshots exactly when the version crosses a multiple of the interval") {
            // Act + Assert
            withClue("still inside the first interval") { crossing(0, 5) shouldBe false }
            withClue("right below the boundary") { crossing(0, 9) shouldBe false }
            withClue("landed on the boundary") { crossing(0, 10) shouldBe true }
            withClue("crossed it by one") { crossing(9, 10) shouldBe true }
            withClue("inside the next interval, its boundary already taken") { crossing(10, 15) shouldBe false }
            withClue("still inside") { crossing(15, 19) shouldBe false }
            withClue("the next boundary") { crossing(15, 20) shouldBe true }
        }

        test("one save appending many events snapshots once, not once per boundary") {
            // Act + Assert - 25 events in one go cross two boundaries
            crossing(0, 25) shouldBe true
        }

        test("a save that recorded nothing never snapshots") {
            // Act + Assert
            crossing(10, 10) shouldBe false
            crossing(0, 0) shouldBe false
        }

        test("an interval of one snapshots on every version") {
            // Arrange
            val everyVersion = VersionIntervalSnapshotStrategy(interval = 1)

            // Act + Assert
            everyVersion.shouldSnapshot(previousVersion = 0, newVersion = 1) shouldBe true
            everyVersion.shouldSnapshot(previousVersion = 7, newVersion = 8) shouldBe true
            everyVersion.shouldSnapshot(previousVersion = 8, newVersion = 8) shouldBe false
        }

        test("evaluates aggregate persisted and current versions") {
            // Arrange
            val everyTwo = VersionIntervalSnapshotStrategy(interval = 2)
            val counter = Counter.new()

            // Act + Assert
            counter.increment()
            everyTwo.shouldSnapshot(counter) shouldBe false

            counter.increment()
            everyTwo.shouldSnapshot(counter) shouldBe true
        }
    })
