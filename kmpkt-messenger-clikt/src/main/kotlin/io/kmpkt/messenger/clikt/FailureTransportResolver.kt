package io.kmpkt.messenger.clikt

import com.github.ajalt.clikt.core.UsageError
import io.kmpkt.messenger.bus.MessengerCatalog
import io.kmpkt.messenger.transport.ListableTransport

internal fun MessengerCatalog.resolveFailureTransport(transportName: String?): ListableTransport {
    if (failureTransports.isEmpty()) throw UsageError("No failure transports are registered.")
    val transport =
        if (transportName != null) {
            failureTransports[transportName]
                ?: throw UsageError("Unknown failure transport '$transportName'. Available: ${failureTransports.keys.sorted()}")
        } else {
            val distinct = failureTransports.values.distinct()
            if (distinct.size > 1) {
                throw UsageError("Multiple failure transports are registered (${failureTransports.keys.sorted()}); pass --transport.")
            }
            distinct.single()
        }
    return transport as? ListableTransport
        ?: throw UsageError("Failure transport does not implement ListableTransport.")
}

internal fun requireIdsOrAll(
    ids: List<String>,
    all: Boolean,
) {
    if (all && ids.isNotEmpty()) throw UsageError("Cannot specify both failed message IDs and --all.")
    if (!all && ids.isEmpty()) throw UsageError("Specify at least one failed message ID or pass --all.")
}
