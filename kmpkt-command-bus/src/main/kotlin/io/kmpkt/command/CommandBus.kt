package io.kmpkt.command

import io.kmpkt.messenger.bus.MessageBus

/**
 * Puts each command on a [MessageBus], so a command gets that bus's middleware, logging, routing and - if
 * it's routed to a queued transport - its retries, instead of running inline.
 *
 * Thin on purpose. It exists so that an application states what it is dispatching ("a command") rather than
 * how ("a message on the bus named `command`"), and so that a policy can be handed something narrower than a
 * whole message bus. Everything else is the messenger's job.
 */
public class CommandBus(
    private val bus: MessageBus,
) {
    public suspend fun dispatch(command: Command) {
        bus.dispatch(command)
    }
}
