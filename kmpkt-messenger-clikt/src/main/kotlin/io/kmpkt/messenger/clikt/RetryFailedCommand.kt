package io.kmpkt.messenger.clikt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.kmpkt.messenger.bus.MessengerCatalog
import kotlinx.coroutines.runBlocking

/**
 * Re-dispatches failed messages from a failure transport onto the configured buses and removes
 * them once dispatch succeeds.
 */
public class RetryFailedCommand(
    private val catalog: MessengerCatalog,
) : CliktCommand(name = "messenger:failed:retry") {
    private val ids: List<String> by argument(help = "Failed message IDs to retry.").multiple()

    private val all: Boolean by option("--all", help = "Retry all failed messages in the failure transport.").flag()

    private val transport: String? by option("--transport", help = "Source transport name whose failure queue to retry.")

    override fun help(context: Context): String = "Retry one or more failed messages."

    override fun run() {
        requireIdsOrAll(ids, all)
        val failureTransport = catalog.resolveFailureTransport(transport)
        val bus = catalog.routableBus()

        runBlocking {
            var retried = 0
            if (all) {
                failureTransport.all().collect { entry ->
                    bus.dispatch(entry.envelope)
                    failureTransport.remove(entry.id)
                    retried++
                }
            } else {
                val targets =
                    ids.map { id ->
                        failureTransport.find(id)
                            ?: throw UsageError("Failed message '$id' was not found.")
                    }
                for (entry in targets) {
                    bus.dispatch(entry.envelope)
                    failureTransport.remove(entry.id)
                    retried++
                }
            }
            echo("Retried $retried failed message(s).")
        }
    }
}
