package io.kmpkt.policy

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.messenger.message.Message

/**
 * "Run the policies against this event" - what a [PolicyTransport] emits onto the bus for each event it claims.
 *
 * The event is wrapped rather than published as itself, which keeps `DomainEvent` out of the messenger's
 * world: an application's events stay domain types that happen to be stored, instead of also having to be
 * [Message]s. One message type for every policy, because one runner serves all of them.
 */
public data class ExecutePolicyCommandsForEvent(
    public val event: DomainEvent<*>,
) : Message
