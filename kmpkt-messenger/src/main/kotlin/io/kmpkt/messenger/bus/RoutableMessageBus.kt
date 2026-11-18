package io.kmpkt.messenger.bus

import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.stamp.BusNameStamp

/**
 * [MessageBus] that routes an [Envelope] to the bus named by its [BusNameStamp], falling back to
 * [fallback] when no [BusNameStamp] is present.
 */
public class RoutableMessageBus(
    private val buses: Map<String, MessageBus>,
    private val fallback: MessageBus? = null,
) : MessageBus {
    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

    override suspend fun dispatch(envelope: Envelope): Envelope {
        val busName = envelope.last<BusNameStamp>()?.busName
        val target =
            if (busName != null) {
                buses[busName]
                    ?: error("Bus '$busName' referenced by BusNameStamp is not registered - available: ${buses.keys.sorted()}")
            } else {
                fallback
                    ?: error("Envelope has no BusNameStamp and no fallback MessageBus is configured")
            }

        return target.dispatch(envelope)
    }
}
