package io.kmpkt.projection

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.messenger.message.Message

/**
 * "Apply this event to this projection" - what a [ProjectionTransport] emits onto the bus for each event it
 * reads.
 *
 * The event is wrapped rather than published as itself, for two reasons. It names which projection is meant,
 * so one handler serves every projection and two projections of the same event stay independent. And it keeps
 * `DomainEvent` out of the messenger's world: an application's events stay domain types that happen to be
 * stored, instead of also having to be [Message]s.
 */
public data class ProjectEvent(
    public val projection: String,
    public val event: DomainEvent<*>,
) : Message
