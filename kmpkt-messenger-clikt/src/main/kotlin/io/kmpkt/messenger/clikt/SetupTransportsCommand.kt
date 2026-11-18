package io.kmpkt.messenger.clikt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import io.kmpkt.messenger.bus.MessengerCatalog
import io.kmpkt.messenger.transport.SetupableTransport
import kotlinx.coroutines.runBlocking

/**
 * Provisions backing infrastructure (database schema, queues, topics) for all or selected
 * [SetupableTransport] instances registered in [MessengerCatalog] (Symfony `messenger:setup-transports` style).
 */
public class SetupTransportsCommand(
    private val catalog: MessengerCatalog,
) : CliktCommand(name = "messenger:setup-transports") {
    private val transports: List<String> by argument(
        help = "Transport names to set up (defaults to all registered transports).",
    ).multiple()

    override fun help(context: Context): String = "Set up backing infrastructure for configured transports."

    override fun run() {
        val names = transports.ifEmpty { catalog.transports.keys.toList() }
        val unknown = names.filter { it !in catalog.transports }
        if (unknown.isNotEmpty()) {
            throw UsageError("Unknown transport(s): ${unknown.joinToString()}. Registered: ${catalog.transports.keys.joinToString()}.")
        }

        runBlocking {
            names.forEach { name ->
                val transport = catalog.transports.getValue(name)
                if (transport is SetupableTransport) {
                    transport.setup()
                    echo("Set up transport \"$name\".")
                } else {
                    echo("Transport \"$name\" does not require setup.")
                }
            }
        }
    }
}
