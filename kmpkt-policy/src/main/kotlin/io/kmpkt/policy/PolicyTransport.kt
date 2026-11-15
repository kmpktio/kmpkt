package io.kmpkt.policy

import io.kmpkt.event.store.EventStore
import io.kmpkt.event.store.stamp.EventPositionStamp
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.persistence.KeyValueStore
import io.kmpkt.persistence.Persistence
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The event log, as a [Transport] for the policies: [receive] claims the next event, [ack] marks it done,
 * [reject] marks it failed.
 *
 * **One instance for every policy**, not one per policy. **Exactly one event in flight**: [receive] returns
 * one envelope or none, and claims nothing new while a claimed event is unsettled.
 *
 * **A rejected event is recorded and left behind.** The runner moves on, and the failed position is kept in
 * the bookkeeping state.
 */
public class PolicyTransport(
    private val persistence: Persistence,
    private val eventStore: EventStore,
    private val name: String = "policies",
    @Suppress("unused")
    private val collectGarbageEvery: Duration = 1.hours,
) : Transport {
    private val stateStore: KeyValueStore = persistence.keyValueStore(NAMESPACE)

    /** Records the starting position - the head of the log - if these policies have never run. */
    public suspend fun setup() {
        persistence.inTransaction {
            if (loadState() == null) {
                saveState(PolicyState(lastPosition = eventStore.latestPosition()))
            }
        }
    }

    override suspend fun send(envelope: Envelope): Envelope =
        error("A policy transport only reads the event log; append events through an aggregate instead")

    /** The next unprocessed event, wrapped and stamped with its position - or nothing. Never more than one. */
    override suspend fun receive(): List<Envelope> =
        persistence.inTransaction {
            val state = loadState() ?: PolicyState(lastPosition = 0L)
            if (state.inFlightPosition != null) return@inTransaction emptyList()

            val next =
                eventStore.loadFrom(state.lastPosition, limit = 1).firstOrNull()
                    ?: return@inTransaction emptyList()

            saveState(state.copy(inFlightPosition = next.position))

            listOf(
                Envelope(ExecutePolicyCommandsForEvent(next.event))
                    .with(EventPositionStamp(next.position)),
            )
        }

    override suspend fun ack(envelope: Envelope) {
        val position = envelope.positionOrFail()
        persistence.inTransaction {
            val state = loadState() ?: error("Policy runner '$name' was never set up")
            require(state.inFlightPosition == position) {
                "Policy runner '$name' cannot ack position $position: in-flight is ${state.inFlightPosition}"
            }
            saveState(
                state.copy(
                    lastPosition = maxOf(state.lastPosition, position),
                    inFlightPosition = null,
                ),
            )
        }
    }

    override suspend fun reject(envelope: Envelope) {
        val position = envelope.positionOrFail()
        persistence.inTransaction {
            val state = loadState() ?: error("Policy runner '$name' was never set up")
            require(state.inFlightPosition == position) {
                "Policy runner '$name' cannot reject position $position: in-flight is ${state.inFlightPosition}"
            }
            saveState(
                state.copy(
                    lastPosition = maxOf(state.lastPosition, position),
                    inFlightPosition = null,
                    failedPositions = state.failedPositions + position,
                ),
            )
        }
    }

    private suspend fun loadState(): PolicyState? =
        stateStore.get(name)?.let(::decodeState)

    private suspend fun saveState(state: PolicyState) {
        stateStore.put(name, encodeState(state))
    }

    private fun Envelope.positionOrFail(): Long =
        last<EventPositionStamp>()?.position
            ?: error("An envelope from policy runner '$name' came back without its EventPositionStamp")

    private data class PolicyState(
        val lastPosition: Long,
        val inFlightPosition: Long? = null,
        val failedPositions: Set<Long> = emptySet(),
    )

    private companion object {
        private const val NAMESPACE = "policy_state"

        private fun encodeState(state: PolicyState): String =
            listOf(
                state.lastPosition.toString(),
                state.inFlightPosition?.toString().orEmpty(),
                state.failedPositions.joinToString(","),
            ).joinToString("|")

        private fun decodeState(raw: String): PolicyState {
            val parts = raw.split('|', limit = 3)
            val lastPosition = parts[0].toLong()
            val inFlight = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLong()
            val failed =
                parts
                    .getOrNull(2)
                    ?.takeIf { it.isNotEmpty() }
                    ?.split(',')
                    ?.mapTo(mutableSetOf()) { it.toLong() }
                    ?: emptySet()
            return PolicyState(lastPosition, inFlight, failed)
        }
    }
}
