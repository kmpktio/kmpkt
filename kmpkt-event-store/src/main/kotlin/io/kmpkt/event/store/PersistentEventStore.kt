package io.kmpkt.event.store

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.exception.ConcurrencyConflict
import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateType
import io.kmpkt.event.store.serialization.EventSerializer
import io.kmpkt.event.store.serialization.SerializedEvent
import io.kmpkt.persistence.AppendLog
import io.kmpkt.persistence.LogEntry
import io.kmpkt.persistence.LogVersionConflictException
import io.kmpkt.persistence.NewLogEntry
import io.kmpkt.persistence.Persistence
import java.util.UUID

/**
 * [EventStore] backed by any [Persistence] implementation via a named [AppendLog].
 */
public class PersistentEventStore(
    private val appendLog: AppendLog,
    private val eventSerializer: EventSerializer,
    private val checkpointPredicate: EventCheckpointPredicate = EventCheckpointPredicate.NONE,
) : EventStore {
    public constructor(
        persistence: Persistence,
        eventSerializer: EventSerializer,
        checkpointPredicate: EventCheckpointPredicate = EventCheckpointPredicate.NONE,
        logName: String = "events",
    ) : this(persistence.appendLog(logName), eventSerializer, checkpointPredicate)

    override suspend fun <A : AggregateRoot<A>> append(
        type: AggregateType<A>,
        id: AggregateId<A>,
        expectedVersion: Long,
        events: List<DomainEvent<A>>,
    ) {
        if (events.isEmpty()) return
        val entries =
            events.map { event ->
                val serialized = eventSerializer.serialize(event)
                NewLogEntry(
                    id = UUID.randomUUID().toString(),
                    type = serialized.type,
                    payload = serialized.payload,
                    occurredAt = event.occurredAt,
                    checkpoint = checkpointPredicate.isCheckpoint(event),
                )
            }
        try {
            appendLog.append(type.name, id.toString(), expectedVersion, entries)
        } catch (conflict: LogVersionConflictException) {
            throw ConcurrencyConflict(id, expectedVersion, cause = conflict)
        }
    }

    override suspend fun <A : AggregateRoot<A>> load(
        type: AggregateType<A>,
        id: AggregateId<A>,
        fromVersion: Long,
        limit: Int?,
    ): List<DomainEvent<A>> =
        appendLog
            .readStream(
                streamType = type.name,
                streamId = id.toString(),
                fromVersion = fromVersion,
                limit = limit,
                fromLatestCheckpoint = true,
            ).map { entry ->
                @Suppress("UNCHECKED_CAST")
                entry.decode() as DomainEvent<A>
            }

    override suspend fun loadFrom(
        after: Long,
        limit: Int,
    ): List<StoredEvent> =
        appendLog
            .readAfter(afterPosition = after, limit = limit, includeCheckpoints = false)
            .map { entry -> StoredEvent(position = entry.position, event = entry.decode()) }

    override suspend fun latestPosition(): Long = appendLog.latestPosition(includeCheckpoints = false)

    private fun LogEntry.decode(): DomainEvent<*> = eventSerializer.deserialize(SerializedEvent(type, payload))
}
