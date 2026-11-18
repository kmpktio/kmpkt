package io.kmpkt.messenger.bus

import io.kmpkt.messenger.transport.Transport

/**
 * Snapshot of all buses and queued transports configured on the messenger, used by multi-transport
 * workers and CLI commands such as `messenger:consume`.
 */
public class MessengerCatalog(
    public val buses: Map<String, MessageBus>,
    public val transports: Map<String, Transport>,
    public val failureTransports: Map<String, Transport> = emptyMap(),
    public val defaultBus: MessageBus? = null,
) {
    public fun routableBus(): RoutableMessageBus = RoutableMessageBus(buses, fallback = defaultBus)
}
