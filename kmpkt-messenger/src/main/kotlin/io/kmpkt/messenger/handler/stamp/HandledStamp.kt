package io.kmpkt.messenger.handler.stamp

import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.message.Stamp
import java.time.Instant
import kotlin.reflect.KClass

/** A message was handled by [handlerClass] at [handledAt], with [result]. */
public data class HandledStamp(
    public val result: Any?,
    public val handlerClass: KClass<MessageHandler<*, *>>,
    public val handledAt: Instant,
) : Stamp
