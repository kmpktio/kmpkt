package io.kmpkt.messenger.fixture

import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler

internal data class Greet(
    val name: String,
) : Message

internal class GreetHandler : MessageHandler<Greet, String> {
    val invocations = mutableListOf<Greet>()

    override suspend fun invoke(message: Greet): String {
        invocations += message
        return "Hello, ${message.name}!"
    }
}

internal class FailingHandler(
    private val error: Throwable,
) : MessageHandler<Greet, String> {
    override suspend fun invoke(message: Greet): String = throw error
}
