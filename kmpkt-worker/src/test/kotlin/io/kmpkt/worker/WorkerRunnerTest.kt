package io.kmpkt.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.slf4j.helpers.NOPLogger
import kotlin.time.Duration.Companion.milliseconds

class WorkerRunnerTest :
    FunSpec({
        test("starts managed workers and registers a shutdown hook that stops them") {
            val started = CompletableDeferred<Unit>()
            val worker =
                ManagedWorker(
                    name = "jobs",
                    work =
                        Work {
                            started.complete(Unit)
                            delay(5.milliseconds)
                            1
                        },
                    logger = NOPLogger.NOP_LOGGER,
                    pollInterval = 5.milliseconds,
                )
            var shutdownHook: Thread? = null
            val runner = WorkerRunner(listOf(worker), onShutdown = { shutdownHook = it })

            runner.run(emptyArray())
            started.await()
            worker.isRunning shouldBe true

            shutdownHook.shouldNotBeNull().run()
            worker.isRunning shouldBe false
        }

        test("close stops running managed workers without a shutdown hook") {
            val started = CompletableDeferred<Unit>()
            val worker =
                ManagedWorker(
                    name = "jobs",
                    work =
                        Work {
                            started.complete(Unit)
                            delay(5.milliseconds)
                            1
                        },
                    logger = NOPLogger.NOP_LOGGER,
                    pollInterval = 5.milliseconds,
                )
            val runner = WorkerRunner(listOf(worker), onShutdown = null)

            runner.run(emptyArray())
            started.await()
            runner.close()

            worker.isRunning shouldBe false
        }

        test("skips startup and shutdown hook registration when worker list is empty") {
            var shutdownHook: Thread? = null
            val runner = WorkerRunner(emptyList(), onShutdown = { shutdownHook = it })

            runner.run(emptyArray())

            shutdownHook.shouldBeNull()
        }
    })
