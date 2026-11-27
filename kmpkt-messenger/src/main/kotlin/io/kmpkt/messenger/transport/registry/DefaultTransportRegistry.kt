package io.kmpkt.messenger.transport.registry

import io.kmpkt.messenger.transport.Transport

/**
 * Map-backed [TransportRegistry] resolving registered [Transport] instances by name.
 */
public class DefaultTransportRegistry(
    private val transports: Map<String, Transport> = emptyMap(),
) : TransportRegistry {
    override fun get(name: String): Transport =
        transports[name]
            ?: error("Transport '$name' not found - registered: ${transports.keys.sorted()}")

    override fun all(): Map<String, Transport> = transports
}
