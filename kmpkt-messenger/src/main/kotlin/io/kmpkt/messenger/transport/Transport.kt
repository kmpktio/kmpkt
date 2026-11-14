package io.kmpkt.messenger.transport

import io.kmpkt.messenger.message.Envelope

/** A delivery mechanism for envelopes - e.g. an in-process channel, a queue, or straight back to the bus. */
public interface Transport {
    /**
     * Sends [envelope], returning it as this transport left it. A queueing transport returns it unchanged; one
     * that handles the message there and then (see [SyncTransport]) returns what came back from handling it, so
     * the result survives all the way up to the original `dispatch` call.
     */
    public suspend fun send(envelope: Envelope): Envelope

    /** Envelopes ready to be processed, or an empty list if none are. */
    public suspend fun receive(): List<Envelope>

    public suspend fun ack(envelope: Envelope)

    /** [envelope] failed to process; the transport decides whether/how to retry it. */
    public suspend fun reject(envelope: Envelope)
}
