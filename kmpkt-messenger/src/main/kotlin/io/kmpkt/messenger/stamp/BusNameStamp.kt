package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Stamp

/**
 * Records the name of the [io.kmpkt.messenger.bus.MessageBus] an envelope was originally
 * dispatched to, so workers and failure-transport replays know which bus owns it.
 */
public data class BusNameStamp(
    public val busName: String,
) : Stamp
