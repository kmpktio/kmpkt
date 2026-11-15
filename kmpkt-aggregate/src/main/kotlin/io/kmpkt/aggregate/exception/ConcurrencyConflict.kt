package io.kmpkt.aggregate.exception

import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.messenger.retry.RecoverableException

/**
 * Optimistic locking failed: another writer appended to this aggregate's stream since it was loaded.
 *
 * Recoverable: whoever lost reloads the aggregate and decides again, which is what a retry of the command
 * that caused this does.
 */
public class ConcurrencyConflict(
    id: AggregateId<*>,
    expectedVersion: Long,
    cause: Throwable? = null,
) : AggregateException(id, "$id is not at expected version $expectedVersion", cause),
    RecoverableException
