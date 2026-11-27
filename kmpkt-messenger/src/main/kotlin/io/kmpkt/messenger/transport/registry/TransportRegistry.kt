package io.kmpkt.messenger.transport.registry

import io.kmpkt.messenger.transport.Transport

/** Resolves registered [Transport] instances by name. */
public interface TransportRegistry {
    /** Resolves the registered [Transport] for [name], or throws [IllegalStateException] if unknown. */
    public fun get(name: String): Transport

    /** Snapshot of all registered transports keyed by name. */
    public fun all(): Map<String, Transport>
}
