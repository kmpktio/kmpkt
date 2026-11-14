package io.kmpkt.command

import io.kmpkt.messenger.message.MessageHandler

/**
 * Carries out one kind of [Command]. Contravariant in [C], so a handler written against a command's supertype
 * also serves its subtypes.
 *
 * A handler declares nothing about *which* command type it takes beyond its own signature. It is a
 * [MessageHandler] for that type, so it is registered with a bus as it is - by hand, or by wiring that reads
 * the type argument.
 */
public interface CommandHandler<in C : Command> : MessageHandler<C, Unit> {
    public suspend fun handle(command: C)

    /** What the bus calls. A command handler is the message handler for its command. */
    override suspend fun invoke(message: C): Unit = handle(message)
}
