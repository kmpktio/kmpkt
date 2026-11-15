package io.kmpkt.example.messenger.inbound.runner

import io.kmpkt.di.ApplicationRunner
import io.kmpkt.example.messenger.domain.command.PlaceOrder
import io.kmpkt.example.messenger.domain.command.SendReceipt
import io.kmpkt.example.messenger.outbound.logger.ObservableLogger
import io.kmpkt.messenger.bus.MessageBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Simulates an external client interacting with the running application while the background worker
 * processes queued messages.
 */
class DemoInteractionRunner(
    private val commands: MessageBus,
    private val logger: ObservableLogger,
) : ApplicationRunner {
    override fun run(args: Array<String>) =
        runBlocking {
            // 1. Synchronous command: PlaceOrderHandler runs inline and publishes OrderPlaced on the events bus.
            commands.dispatch(PlaceOrder(orderId = "ord-42", amount = 99, actor = "alice"))

            // 2. Asynchronous commands: routed to the "async-jobs" transport and processed by the background worker.
            //    ord-42 fails once (transient SMTP error) and succeeds on retry #2.
            //    ord-broken fails permanently and is moved to the dead-letter transport after retries are exhausted.
            commands.dispatch(SendReceipt(orderId = "ord-42"))
            commands.dispatch(SendReceipt(orderId = "ord-broken"))

            // 3. Wait until the background worker has processed both the retried receipt and the dead-lettered one.
            withTimeout(10.seconds) {
                while (
                    logger.entries.none { it.startsWith("receipt: ") } ||
                    logger.entries.none { it.startsWith("dead-letter: ") }
                ) {
                    delay(20.milliseconds)
                }
            }

            val snapshot = logger.entries.toList()

            // CorrelationMiddleware assigns one ID per command bus pass:
            // corr-1 (PlaceOrder), corr-2/3 (SendReceipt ord-42 + retry), corr-4/5 (SendReceipt ord-broken + retry).
            check(
                snapshot.filter { it.startsWith("correlation: ") } ==
                    listOf(
                        "correlation: corr-1",
                        "correlation: corr-2",
                        "correlation: corr-3",
                        "correlation: corr-4",
                        "correlation: corr-5",
                    ),
            ) {
                "Unexpected correlations in log: $snapshot"
            }
            // AuditOrderPlacedHandler (priority 10) always runs before AnalyticsOrderPlacedHandler (priority 0).
            check(
                snapshot.filter { it.startsWith("audit: ") || it.startsWith("analytics: ") } ==
                    listOf(
                        "audit: ord-42 placed by alice",
                        "analytics: recorded revenue 99 for ord-42",
                    ),
            ) {
                "Unexpected event log entries: $snapshot"
            }
            check("receipt: delivered ord-42#2" in snapshot) { "Missing delivered receipt in: $snapshot" }
            check("dead-letter: HandlerFailedException" in snapshot) { "Missing dead-letter in: $snapshot" }

            snapshot.forEach(::println)
        }
}
