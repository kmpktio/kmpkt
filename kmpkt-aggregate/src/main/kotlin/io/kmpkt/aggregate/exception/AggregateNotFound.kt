package io.kmpkt.aggregate.exception

import io.kmpkt.aggregate.value.AggregateId

/** Thrown when there's no recorded history for an id a repository was asked to load. */
public class AggregateNotFound(
    id: AggregateId<*>,
) : AggregateException(id, "Aggregate not found for id: $id")
