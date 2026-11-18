package io.kmpkt.messenger.clikt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.kmpkt.messenger.bus.MessengerCatalog
import io.kmpkt.messenger.worker.TransportWork
import io.kmpkt.worker.LockedWork
import io.kmpkt.worker.UnmanagedWorker
import io.kmpkt.worker.Work
import io.kmpkt.worker.WorkLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Consumes messages from one or more prioritized transports (Symfony `messenger:consume` style),
 * routing each envelope via `BusNameStamp` unless `--bus` overrides the target bus.
 */
public class ConsumeCommand(
    private val catalog: MessengerCatalog,
    private val logger: Logger = LoggerFactory.getLogger(ConsumeCommand::class.java),
    private val workLock: WorkLock? = null,
    private val clock: Clock = Clock.systemUTC(),
) : CliktCommand(name = "messenger:consume") {
    private val receivers: List<String> by argument(
        help = "Transport names to consume in priority order (highest first).",
    ).multiple()

    private val all: Boolean by option("--all", help = "Consume all registered queued transports.").flag()

    private val bus: String? by option("--bus", help = "Force all consumed envelopes onto this bus instead of using BusNameStamp.")

    private val limit: Int? by option("--limit", help = "Stop after processing this many messages.").int().restrictTo(min = 1)

    private val timeLimitSeconds: Long? by
        option("--time-limit", help = "Stop after running for this many seconds.").long().restrictTo(min = 1L)

    private val failureLimit: Int? by option("--failure-limit", help = "Stop after this many failed messages.").int().restrictTo(min = 1)

    private val sleepMs: Long by option("--sleep", help = "Idle poll delay in milliseconds.").long().restrictTo(min = 0L).default(500L)

    private val lock: String? by option("--lock", help = "Optional WorkLock name for single-active-consumer execution.")

    override fun help(context: Context): String = "Consume messages from one or more configured transports."

    override fun run() {
        val selected = resolveTransports()
        val targetBus = resolveBus()
        var failures = 0
        val baseWork =
            TransportWork(
                transports = selected,
                messageBus = targetBus,
                logger = logger,
                failureTransports = catalog.failureTransports,
                onFailure = { failures++ },
            )
        val work = wrapWithLock(baseWork)

        runBlocking {
            val maxMessages = limit
            val maxFailures = failureLimit
            val deadline = timeLimitSeconds?.let { clock.instant().plusSeconds(it) }
            if (maxMessages != null || maxFailures != null || deadline != null) {
                var processedMessages = 0
                while (
                    (maxMessages == null || processedMessages < maxMessages) &&
                    (maxFailures == null || failures < maxFailures) &&
                    (deadline == null || clock.instant().isBefore(deadline))
                ) {
                    val processed = work.run()
                    processedMessages += processed
                    if (processed == 0) {
                        val shouldContinue =
                            (maxMessages == null || processedMessages < maxMessages) &&
                                (maxFailures == null || failures < maxFailures)
                        if (!shouldContinue) break
                        if (deadline != null) {
                            val remainingMs = Duration.between(clock.instant(), deadline).toMillis()
                            if (remainingMs <= 0) break
                            delay(minOf(sleepMs, remainingMs))
                        } else {
                            delay(sleepMs)
                        }
                    }
                }
            } else {
                UnmanagedWorker("messenger:consume", work, logger, pollInterval = sleepMs.milliseconds).run()
            }
        }
    }

    private fun resolveTransports() =
        when {
            all -> {
                if (catalog.transports.isEmpty()) throw UsageError("No queued transports are registered.")
                catalog.transports
            }
            receivers.isNotEmpty() -> {
                linkedMapOf<String, io.kmpkt.messenger.transport.Transport>().apply {
                    receivers.forEach { name ->
                        val transport =
                            catalog.transports[name]
                                ?: throw UsageError("Unknown transport '$name'. Available: ${catalog.transports.keys.sorted()}")
                        put(name, transport)
                    }
                }
            }
            else -> throw UsageError("Specify at least one transport receiver or pass --all.")
        }

    private fun resolveBus() =
        if (bus != null) {
            catalog.buses[bus] ?: throw UsageError("Unknown bus '$bus'. Available: ${catalog.buses.keys.sorted()}")
        } else {
            catalog.routableBus()
        }

    private fun wrapWithLock(work: Work): Work {
        val lockName = lock ?: return work
        val provider = workLock ?: throw UsageError("Cannot use --lock '$lockName': no WorkLock is registered.")
        return LockedWork(work, provider, lockName)
    }
}
