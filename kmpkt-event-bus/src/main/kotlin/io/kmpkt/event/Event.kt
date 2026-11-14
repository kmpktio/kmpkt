package io.kmpkt.event

import io.kmpkt.messenger.message.Message

/**
 * Something that happened, stated in the past tense: "the list was sorted", "the item was checked". The
 * counterpart to a command - a command asks for something and has exactly one handler, an event reports
 * something and has as many as care.
 *
 * Only the marker. What a concrete event carries is the application's business. An event is a [Message], so
 * it can travel over a bus.
 *
 * Under event sourcing, `kmpkt-event-store`'s `DomainEvent` extends this with the aggregate, version and
 * time that a stored event needs - and then nothing publishes events, because the store itself is what
 * consumers read from. See [EventBus] for which of the two an application wants.
 */
public interface Event : Message
