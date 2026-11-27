package io.kmpkt.messenger.transport.routing

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.transport.Transport

/** Resolved target [transport] paired with its [transportName]. */
public data class RoutedTransport(
    public val transportName: String,
    public val transport: Transport,
)

/** Selects and resolves the [Transport] for an outbound [Envelope], or `null` for inline handling. */
public interface TransportRouter {
    public fun route(envelope: Envelope): RoutedTransport?
}
