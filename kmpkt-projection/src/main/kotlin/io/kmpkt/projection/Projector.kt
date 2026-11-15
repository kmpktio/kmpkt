package io.kmpkt.projection

import io.kmpkt.aggregate.event.DomainEvent

/**
 * "Given this event, then these rows" - one event type [E], one projection of row type [R]. The projection
 * counterpart of a `Policy`: it decides, it does not write. What it returns is applied by whatever owns the
 * projection's storage.
 *
 * ```kotlin
 * class WhenListRenamedRenameAccessibleLists : Projector<ListRenamed, AccessibleListRow> {
 *     override suspend fun project(event: ListRenamed, view: ProjectionView<AccessibleListRow>) =
 *         view.findAll(match(AccessibleListRow::listId to event.listId.value))
 *             .map { row -> Put(row.copy(title = event.title)) }
 * }
 * ```
 *
 * [view] is why this works at all. An event rarely carries everything a row needs - `ListRenamed` knows the
 * new title and nothing about collaborators or item counts - so a projector that could only look at its event
 * would have to write rows full of holes. Passing a read-only [ProjectionView] gives the handler a safe way
 * to look up existing state: reading is allowed; writing is not.
 *
 * Contravariant in [E], so a projector written against an aggregate's whole event hierarchy can be registered
 * for each concrete type that should drive it.
 */
public interface Projector<in E : DomainEvent<*>, R : Any> {
    public suspend fun project(
        event: E,
        view: ProjectionView<R>,
    ): List<ProjectionChange<R>>
}
