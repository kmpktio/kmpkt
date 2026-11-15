package io.kmpkt.projection

import io.kmpkt.messenger.message.MessageHandler

/**
 * Applies one [ProjectEvent]: asks the projectors what the event changes, and writes that to the store.
 *
 * The only place a projection is written. A [Projector] decides and a [ProjectionStore] writes, and this is
 * what joins the two - so a projector stays a pure function of its event plus what it can read, and the write
 * happens once, in one place, where a transaction could be drawn around it.
 *
 * One handler per projection, since each has its own store and projectors. It checks the message is addressed
 * to this projection, because a bus shared by several would otherwise let one projection's events be applied
 * to another's rows.
 */
public class ProjectEventHandler<R : Any>(
    private val projection: String,
    private val store: ProjectionStore<R>,
    private val projectors: ProjectionExecutor<R>,
) : MessageHandler<ProjectEvent, Unit> {
    override suspend fun invoke(message: ProjectEvent) {
        require(message.projection == projection) {
            "Projection '$projection' was handed an event addressed to '${message.projection}'"
        }

        store.apply(projectors.changesFor(message.event, store))
    }
}
