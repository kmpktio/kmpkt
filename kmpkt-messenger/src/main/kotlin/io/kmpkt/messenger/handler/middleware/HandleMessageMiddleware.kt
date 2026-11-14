package io.kmpkt.messenger.handler.middleware

import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.handler.HandlerRegistry
import io.kmpkt.messenger.handler.exception.HandlerFailedException
import io.kmpkt.messenger.handler.exception.NoHandlerForMessageException
import io.kmpkt.messenger.handler.stamp.HandledStamp
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain
import java.time.Clock
import kotlin.reflect.KClass

/**
 * Invokes the registered handler(s) for the envelope's message - the last middleware in the chain, since
 * nothing after it has anything left to do.
 */
public class HandleMessageMiddleware(
    private val handlers: HandlerRegistry,
    private val policy: HandlerPolicy = HandlerPolicy.SINGLE,
    private val clock: Clock,
) : Middleware {
    override suspend fun handle(
        envelope: Envelope,
        next: MiddlewareChain,
    ): Envelope {
        val handlers = handlers.getHandlers(envelope.message::class)

        when (policy) {
            HandlerPolicy.SINGLE -> {
                if (handlers.isEmpty()) throw NoHandlerForMessageException(envelope.message)
                check(handlers.size <= 1) {
                    "Multiple handlers found for ${envelope.message::class.simpleName} but policy is SINGLE"
                }
            }
            HandlerPolicy.MULTIPLE -> Unit
        }

        val failures = mutableListOf<Throwable>()
        var result = envelope

        handlers
            // Don't run a handler again if this exact envelope already went through it.
            .filterNot { envelope.last<HandledStamp>()?.handlerClass == it::class }
            .forEach { handler ->
                @Suppress("UNCHECKED_CAST")
                val typedHandler = handler as MessageHandler<Message, Any?>

                @Suppress("UNCHECKED_CAST")
                val handlerClass = handler::class as KClass<MessageHandler<*, *>>

                val handledResult =
                    try {
                        typedHandler(envelope.message)
                    } catch (e: Throwable) {
                        failures.add(e)
                        return@forEach
                    }

                result = result.with(HandledStamp(result = handledResult, handlerClass = handlerClass, handledAt = clock.instant()))
            }

        if (failures.isNotEmpty()) throw HandlerFailedException(envelope.message, *failures.toTypedArray(), envelope = result)

        return result
    }
}
