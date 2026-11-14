package io.kmpkt.messenger.bus

import io.kmpkt.messenger.handler.HandlerRegistry
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.middleware.MiddlewareChain

/** Runs every envelope through [middleware], in order - the last middleware is usually `HandleMessageMiddleware`. */
public class DefaultMessageBus(
    private val middleware: List<Middleware> = emptyList(),
    private val handlerRegistry: HandlerRegistry,
) : MessageBus {
    override suspend fun dispatch(message: Message): Envelope = dispatch(Envelope(message))

    override suspend fun dispatch(envelope: Envelope): Envelope = buildChain()(envelope)

    private fun buildChain(): MiddlewareChain {
        val passThrough: MiddlewareChain = { envelope -> envelope }
        return middleware.foldRight(passThrough) { mw, next -> { envelope -> mw.handle(envelope, next) } }
    }
}
