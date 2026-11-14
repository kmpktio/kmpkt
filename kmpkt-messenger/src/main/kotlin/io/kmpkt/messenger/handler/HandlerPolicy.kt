package io.kmpkt.messenger.handler

/** How many handlers [io.kmpkt.messenger.handler.middleware.HandleMessageMiddleware] requires per message. */
public enum class HandlerPolicy {
    /** Exactly one handler, e.g. a command bus. */
    SINGLE,

    /** Zero or more handlers, e.g. an event bus. */
    MULTIPLE,
}
