package io.kmpkt.messenger.fixture

import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message

public class RecordingMessageBus : MessageBus {
    public val dispatched: MutableList<Message> = mutableListOf()

    override suspend fun dispatch(message: Message): Envelope {
        dispatched += message
        return Envelope(message)
    }

    override suspend fun dispatch(envelope: Envelope): Envelope {
        dispatched += envelope.message
        return envelope
    }
}
