package io.kmpkt.projection

import io.kmpkt.event.store.EventStore
import io.kmpkt.event.store.stamp.EventPositionStamp
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.persistence.KeyValueStore
import io.kmpkt.persistence.Persistence
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Where a projection stands: how far it has got, and whether it is currently backing off after a failure.
 */
public data class ProjectionState(
    public val position: Long,
    public val rejectedAt: Instant? = null,
)

/**
 * The event log, as a [Transport] for one projection: [receive] reads the events after this projection's
 * position, [ack] advances it, [reject] does not.
 *
 * **A rejected event wedges this projection, by design.** The position stays where it was, so the same event
 * comes back on the next pass, for as long as it keeps failing. [retryAfter] only slows the retrying down -
 * it never gives up.
 *
 * The rejection is recorded in [Persistence.keyValues] rather than held in memory, so a wedged projection
 * stays wedged across restarts.
 */
public class ProjectionTransport<R : Any>(
    public val projection: String,
    private val persistence: Persistence,
    private val eventStore: EventStore,
    private val store: ProjectionStore<R>,
    private val projectors: ProjectionExecutor<R>,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSize: Int = 100,
    private val retryAfter: Duration = 1.minutes,
) : Transport {
    private val mutex = Mutex()
    private val positions: KeyValueStore = persistence.keyValueStore(NAMESPACE)

    /** How far this projection has got, or `null` if it has never run. For monitoring and lag. */
    public suspend fun position(): Long? = loadState()?.position

    /**
     * Records the starting position - the beginning of the log - if this projection has never run. Call it
     * before the first [receive]; a projection with no recorded position has nothing for [ack] to advance.
     */
    public suspend fun setup() {
        mutex.withLock {
            persistence.inTransaction { currentState() }
        }
    }

    override suspend fun send(envelope: Envelope): Envelope =
        error("A projection transport only reads the event log; append events through an aggregate instead")

    override suspend fun receive(): List<Envelope> =
        mutex.withLock {
            val state = persistence.inTransaction { currentState() }

            if (state.isBackingOff()) return emptyList()

            val batch = eventStore.loadFrom(state.position, batchSize)
            val handled = batch.filter { stored -> projectors.handles(stored.event) }

            if (handled.isEmpty()) {
                batch.lastOrNull()?.let { last ->
                    saveState(ProjectionState(last.position))
                }
                return emptyList()
            }

            handled.map { stored ->
                Envelope(ProjectEvent(projection, stored.event))
                    .with(EventPositionStamp(stored.position))
            }
        }

    /** Advances the position to this envelope's event, so the next [receive] starts after it. */
    override suspend fun ack(envelope: Envelope) {
        val position = envelope.positionOrFail()
        mutex.withLock {
            saveState(ProjectionState(position))
        }
    }

    /**
     * Leaves the position alone, so the same event comes back, and starts the [retryAfter] backoff.
     */
    override suspend fun reject(envelope: Envelope) {
        envelope.positionOrFail()
        mutex.withLock {
            persistence.inTransaction {
                val state = currentState()
                saveState(state.copy(rejectedAt = clock.instant()))
            }
        }
    }

    /**
     * Discards the projection's rows and its position, so the next [receive] builds it from the first event
     * again.
     */
    public suspend fun reset() {
        mutex.withLock {
            store.clear()
            positions.delete(projection)
        }
    }

    private fun ProjectionState.isBackingOff(): Boolean =
        rejectedAt
            ?.plus(retryAfter.toJavaDuration())
            ?.isAfter(clock.instant())
            ?: false

    private suspend fun currentState(): ProjectionState =
        loadState()
            ?: ProjectionState(0).also { saveState(it) }

    private suspend fun loadState(): ProjectionState? =
        positions.get(projection)?.let(::decodeState)

    private suspend fun saveState(state: ProjectionState) {
        positions.put(projection, encodeState(state))
    }

    private fun Envelope.positionOrFail(): Long =
        last<EventPositionStamp>()?.position
            ?: error("An envelope from projection '$projection' came back without its EventPositionStamp")

    private companion object {
        private const val NAMESPACE = "projection_position"

        private fun encodeState(state: ProjectionState): String =
            "${state.position}|${state.rejectedAt?.toString().orEmpty()}"

        private fun decodeState(raw: String): ProjectionState {
            val parts = raw.split('|', limit = 2)
            val position = parts[0].toLong()
            val rejectedAt = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(Instant::parse)
            return ProjectionState(position, rejectedAt)
        }
    }
}
