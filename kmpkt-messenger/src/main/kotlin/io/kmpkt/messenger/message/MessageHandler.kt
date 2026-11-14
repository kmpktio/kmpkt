package io.kmpkt.messenger.message

/** Handles one [Message] type [M], returning [R] (`Unit` for commands, a result for queries). */
public fun interface MessageHandler<in M : Message, out R> {
    public suspend operator fun invoke(message: M): R
}
