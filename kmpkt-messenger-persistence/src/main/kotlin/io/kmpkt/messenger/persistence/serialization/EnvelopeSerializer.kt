package io.kmpkt.messenger.persistence.serialization

import io.kmpkt.messenger.message.Envelope

/**
 * Turns an [Envelope] into a string for [io.kmpkt.messenger.persistence.transport.PersistentTransport] and back,
 * keeping the transport independent of any concrete serialization library.
 */
public interface EnvelopeSerializer {
    public fun serialize(envelope: Envelope): String

    public fun deserialize(payload: String): Envelope
}
