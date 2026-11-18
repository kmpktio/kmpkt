package io.kmpkt.messenger.clikt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.kmpkt.messenger.bus.MessengerCatalog
import io.kmpkt.messenger.stamp.RedeliveryStamp
import io.kmpkt.messenger.stamp.SentToFailureTransportStamp
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Lists failed messages waiting in a [io.kmpkt.messenger.transport.ListableTransport] failure transport
 * or prints details for a single failed message by ID.
 */
public class ShowFailedCommand(
    private val catalog: MessengerCatalog,
) : CliktCommand(name = "messenger:failed:show") {
    private val id: String? by argument(help = "Optional failed message ID to inspect.").optional()

    private val transport: String? by option("--transport", help = "Source transport name whose failure queue to inspect.")

    private val max: Int? by option("--max", help = "Maximum number of failed messages to display.").int().restrictTo(min = 1)

    override fun help(context: Context): String = "Show failed messages waiting in a failure transport."

    override fun run() {
        val failureTransport = catalog.resolveFailureTransport(transport)

        runBlocking {
            val targetId = id
            if (targetId != null) {
                val entry =
                    failureTransport.find(targetId)
                        ?: throw UsageError("Failed message '$targetId' was not found.")
                val stamp = entry.envelope.last<SentToFailureTransportStamp>()
                val redelivery = entry.envelope.last<RedeliveryStamp>()
                echo("ID: ${entry.id}")
                echo("Message: ${entry.envelope.message::class.simpleName}")
                if (stamp != null) {
                    echo("Origin: ${stamp.originalTransportName}")
                    echo("Error: ${stamp.failure}: ${stamp.failureMessage ?: ""}")
                }
                if (redelivery != null) {
                    echo("Retries: ${redelivery.retryCount}")
                }
            } else {
                val flow = max?.let { failureTransport.all().take(it) } ?: failureTransport.all()
                val entries = flow.toList()
                if (entries.isEmpty()) {
                    echo("No failed messages found.")
                } else {
                    entries.forEach { entry ->
                        val stamp = entry.envelope.last<SentToFailureTransportStamp>()
                        val errorSummary = stamp?.let { "${it.failure}: ${it.failureMessage ?: ""}" } ?: "unknown"
                        echo("${entry.id} | ${entry.envelope.message::class.simpleName} | $errorSummary")
                    }
                }
            }
        }
    }
}
