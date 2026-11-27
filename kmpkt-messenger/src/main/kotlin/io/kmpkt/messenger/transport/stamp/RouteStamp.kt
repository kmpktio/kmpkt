package io.kmpkt.messenger.transport.stamp

import io.kmpkt.messenger.message.Stamp

/**
 * Explicitly selects [transportName] for this single envelope, overriding the configured
 * [io.kmpkt.messenger.transport.routing.TransportRouter] table.
 *
 * Naming `"sync"` forces inline handling on the bus's sync transport regardless of the
 * message type's default route.
 */
public data class RouteStamp(
    public val transportName: String,
) : Stamp
