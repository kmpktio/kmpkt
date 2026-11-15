package io.kmpkt.policy

import io.kmpkt.command.CommandBus
import io.kmpkt.messenger.message.MessageHandler

/**
 * Runs every registered policy against one event and dispatches whatever they decided.
 *
 * The one place a policy's decision turns into an action, which is what keeps a [Policy] a pure function:
 * it answers "which commands", this hands them to the [CommandBus], and nothing in between knows about the
 * other.
 *
 * Commands go out in the order the policies decided them. If one throws, the ones already dispatched stay
 * dispatched and the event is rejected - so a policy that decides several commands has to tolerate the earlier
 * ones having run. Where that is unacceptable, decide one command and let its handler decide the rest.
 */
public class ExecutePolicyCommandsForEventHandler(
    private val policies: PolicyExecutor,
    private val commandBus: Lazy<CommandBus>,
) : MessageHandler<ExecutePolicyCommandsForEvent, Unit> {
    public constructor(
        policies: PolicyExecutor,
        commandBus: CommandBus,
    ) : this(policies, lazyOf(commandBus))

    override suspend fun invoke(message: ExecutePolicyCommandsForEvent) {
        policies.commandsFor(message.event).forEach { command -> commandBus.value.dispatch(command) }
    }
}
