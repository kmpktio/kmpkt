package io.kmpkt.messenger.clikt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.kmpkt.messenger.bus.MessengerCatalog
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Permanently removes failed messages from a failure transport.
 */
public class RemoveFailedCommand(
    private val catalog: MessengerCatalog,
) : CliktCommand(name = "messenger:failed:remove") {
    private val ids: List<String> by argument(help = "Failed message IDs to remove.").multiple()

    private val all: Boolean by option("--all", help = "Remove all failed messages in the failure transport.").flag()

    private val transport: String? by option("--transport", help = "Source transport name whose failure queue to prune.")

    override fun help(context: Context): String = "Remove one or more failed messages from a failure transport."

    override fun run() {
        requireIdsOrAll(ids, all)
        val failureTransport = catalog.resolveFailureTransport(transport)

        runBlocking {
            val targetIds = if (all) failureTransport.all().map { it.id }.toList() else ids
            var removed = 0
            for (id in targetIds) {
                if (!failureTransport.remove(id)) {
                    throw UsageError("Failed message '$id' was not found.")
                }
                removed++
            }
            echo("Removed $removed failed message(s).")
        }
    }
}
