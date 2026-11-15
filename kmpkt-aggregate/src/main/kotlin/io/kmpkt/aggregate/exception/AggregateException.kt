package io.kmpkt.aggregate.exception

import io.kmpkt.aggregate.value.AggregateId

/** A known, expected failure of this library's event-sourcing machinery, as opposed to an unexpected one. */
public abstract class AggregateException(
    public val id: AggregateId<*>,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
