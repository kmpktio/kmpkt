package io.kmpkt.messenger.transport.registry

import io.kmpkt.messenger.message.Message
import kotlin.reflect.KClass

/** Maps a [Message] type to the name of the transport it should be sent through. */
public interface TransportRegistry {
    /** The transport name for [messageType], or `null` if it should be handled synchronously instead. */
    public fun getTransport(messageType: KClass<out Message>): String?
}
