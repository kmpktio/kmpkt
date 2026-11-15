package io.kmpkt.aggregate.value

import io.kmpkt.aggregate.model.AggregateRoot

/**
 * Identifies an aggregate of type [A], so ids of different aggregate types can't be mixed up at compile time.
 * No required shape — [AggregateUuid] is the default, UUID-based implementation; define your own when an
 * aggregate's id needs to carry something else (e.g. the original external id has to stay recoverable from it).
 */
public interface AggregateId<A : AggregateRoot<A>>
