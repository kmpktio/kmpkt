package io.kmpkt.command

import io.kmpkt.messenger.message.Message

/**
 * An intent to change something: "sort this list", "enable that one". What a concrete command looks like, and
 * what dispatching it means, is the application's business - this is only the marker a [CommandBus]
 * accepts and a [CommandHandler] handles.
 *
 * A command is a [Message]: it goes over a message bus, which is the only way it is ever carried out.
 */
public interface Command : Message
