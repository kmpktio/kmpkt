package io.kmpkt.worker

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

private val logger = LoggerFactory.getLogger(UnmanagedWorkerTest::class.java)

private fun worker(
    work: Work,
    retry: RetryStrategy = RetryStrategy.none(),
) = UnmanagedWorker("test", work, logger, pollInterval = 5.milliseconds, retry = retry)

class UnmanagedWorkerTest :
    FunSpec({
        test("lets a failure it gave up on come straight out") {
            // Arrange
            val work = Work { throw IllegalStateException("poisoned") }

            // Act + Assert - dying with the exception is the point of this kind
            shouldThrow<IllegalStateException> { worker(work).run() }.message shouldBe "poisoned"
        }

        test("retries as often as the strategy allows, then fails") {
            // Arrange
            var attempts = 0
            val work =
                Work {
                    attempts += 1
                    throw IllegalStateException("still broken")
                }

            // Act + Assert
            shouldThrow<IllegalStateException> {
                worker(work, RetryStrategy.fixed(times = 2, delay = 1.milliseconds)).run()
            }
            attempts shouldBe 3 // the original attempt plus two retries
        }

        test("a successful pass clears the attempt count, so hiccups never accumulate") {
            // Arrange - every other pass throws, with only one retry allowed at a time
            var passes = 0
            val work =
                Work {
                    passes += 1
                    if (passes % 2 == 1) throw IllegalStateException("hiccup") else 1
                }
            val scope = CoroutineScope(Dispatchers.Default)

            // Act - with accumulating attempts this would have given up on the second failure
            scope.launch { worker(work, RetryStrategy.fixed(times = 1, delay = 1.milliseconds)).run() }
            withTimeout(1000) { while (passes < 8) delay(1) }
            scope.cancel()

            // Assert
            passes shouldBeGreaterThanOrEqualTo 8
        }

        test("stops when the coroutine it runs in is cancelled") {
            // Arrange
            var passes = 0
            val scope = CoroutineScope(Dispatchers.Default)

            // Act
            val job =
                scope.launch {
                    worker(
                        Work {
                            passes += 1
                            0
                        },
                    ).run()
                }
            withTimeout(1000) { while (passes < 1) delay(1) }
            scope.cancel()
            withTimeout(1000) { while (job.isActive) delay(1) }

            // Assert
            job.isActive shouldBe false
        }
    })
