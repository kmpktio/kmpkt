package io.kmpkt.messenger.transport.stamp

import io.kmpkt.messenger.message.Stamp

/**
 * Explicitly picks [transportName] for this one envelope, overriding whatever
 * [io.kmpkt.messenger.transport.registry.TransportRegistry] would otherwise say. For the rare
 * per-message override, not the default routing mechanism - that's the registry.
 *
 * Dropping the stamp falls back to the registry, it doesn't force inline handling. Naming a
 * [io.kmpkt.messenger.transport.SyncTransport] here is how you say "whatever the table says, handle
 * this one inline".
 */
public data class RouteStamp(
    public val transportName: String,
) : Stamp
