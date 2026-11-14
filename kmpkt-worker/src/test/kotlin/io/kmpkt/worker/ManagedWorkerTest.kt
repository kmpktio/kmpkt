package io.kmpkt.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

private val logger = LoggerFactory.getLogger(ManagedWorkerTest::class.java)

class ManagedWorkerTest :
    FunSpec({
        test("runs passes until stopped") {
            // Arrange
            var passes = 0
            val worker =
                ManagedWorker(
                    "test",
                    Work {
                        passes += 1
                        1
                    },
                    logger,
                    pollInterval = 5.milliseconds,
                )

            // Act
            worker.start()
            withTimeout(1000) { while (passes < 3) delay(1) }
            worker.stop()

            // Assert
            worker.isRunning shouldBe false
            passes shouldBeGreaterThanOrEqualTo 3
        }

        test("stop lets the current pass finish instead of cutting it short") {
            // Arrange - a pass that takes a while, and records whether it got to the end
            var finished = false
            val started = CompletableDeferred<Unit>()
            val work =
                Work {
                    started.complete(Unit)
                    delay(50)
                    finished = true
                    0
                }
            val worker = ManagedWorker("test", work, logger, pollInterval = 5.milliseconds)

            // Act - stop while that pass is in flight
            worker.start()
            withTimeout(1000) { started.await() }
            worker.stop()

            // Assert
            finished shouldBe true
        }

        test("a failure it gave up on ends only this worker, and is reported") {
            // Arrange
            val reported = CompletableDeferred<Throwable>()
            val worker =
                ManagedWorker(
                    "test",
                    Work { throw IllegalStateException("poisoned") },
                    logger,
                    pollInterval = 5.milliseconds,
                    retry = RetryStrategy.none(),
                    onGiveUp = { reported.complete(it) },
                )

            // Act
            worker.start()
            val failure = withTimeout(1000) { reported.await() }

            // Assert - reported rather than thrown out of the application's scope
            failure.message shouldBe "poisoned"
            withTimeout(1000) { while (worker.isRunning) delay(1) }
            worker.isRunning shouldBe false
        }

        test("one worker giving up leaves another running") {
            // Arrange
            var healthyPasses = 0
            val gaveUp = CompletableDeferred<Throwable>()
            val broken =
                ManagedWorker(
                    "broken",
                    Work { throw IllegalStateException("boom") },
                    logger,
                    pollInterval = 5.milliseconds,
                    retry = RetryStrategy.none(),
                    onGiveUp = { gaveUp.complete(it) },
                )
            val healthy =
                ManagedWorker(
                    "healthy",
                    Work {
                        healthyPasses += 1
                        1
                    },
                    logger,
                    pollInterval = 5.milliseconds,
                )

            // Act
            healthy.start()
            broken.start()
            withTimeout(1000) { gaveUp.await() }
            val passesWhenBrokenDied = healthyPasses
            withTimeout(1000) { while (healthyPasses <= passesWhenBrokenDied) delay(1) }

            // Assert - still making progress after the other one died
            healthy.isRunning shouldBe true
            healthy.stop()
        }

        test("starting twice does not run two loops") {
            // Arrange
            var passes = 0
            val worker =
                ManagedWorker(
                    "test",
                    Work {
                        passes += 1
                        0
                    },
                    logger,
                    pollInterval = 20.milliseconds,
                )

            // Act
            worker.start()
            worker.start()
            withTimeout(1000) { while (passes < 1) delay(1) }
            delay(50)
            worker.stop()

            // Assert - two loops at a 20ms interval would have run well past this in 50ms
            passes shouldBe 3
        }

        test("stop on a worker that never started is harmless") {
            // Arrange
            val worker = ManagedWorker("test", Work { 0 }, logger)

            // Act + Assert
            worker.stop()
            worker.isRunning shouldBe false
        }

        test("stop cancels a pass that overruns the timeout") {
            // Arrange - a pass that never finishes on its own
            val started = CompletableDeferred<Unit>()
            val work =
                Work {
                    started.complete(Unit)
                    delay(10_000)
                    0
                }
            val worker = ManagedWorker("test", work, logger, pollInterval = 5.milliseconds)

            // Act
            worker.start()
            withTimeout(1000) { started.await() }
            worker.stop(timeout = 20.milliseconds)

            // Assert - the graceful part is the attempt, not a promise
            worker.isRunning shouldBe false
        }

        test("reports the failure it gave up on, not an earlier one it retried past") {
            // Arrange
            var passes = 0
            val reported = CompletableDeferred<Throwable>()
            val work =
                Work {
                    passes += 1
                    throw IllegalStateException("failure $passes")
                }
            val worker =
                ManagedWorker(
                    "test",
                    work,
                    logger,
                    pollInterval = 5.milliseconds,
                    retry = RetryStrategy.fixed(times = 2, delay = 1.milliseconds),
                    onGiveUp = { reported.complete(it) },
                )

            // Act
            worker.start()
            val failure = withTimeout(1000) { reported.await() }

            // Assert
            failure.shouldNotBeNull()
            failure.message shouldBe "failure 3"
        }
    })
