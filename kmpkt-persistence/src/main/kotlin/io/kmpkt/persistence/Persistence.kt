package io.kmpkt.persistence

/**
 * Domain-agnostic storage backend providing named [KeyValueStore], [AppendLog], and [WorkQueue] instances,
 * coroutine-context-propagated transactions, and locks.
 */
public interface Persistence {
    public fun keyValueStore(name: String): KeyValueStore

    public fun appendLog(name: String): AppendLog

    public fun workQueue(name: String): WorkQueue

    /**
     * Runs [block] atomically. Any storage primitive called inside [block] (including nested [inTransaction]
     * calls) participates in the same transaction; an uncaught exception rolls everything back.
     */
    public suspend fun <R> inTransaction(block: suspend () -> R): R

    /**
     * Exclusive use of [name] for as long as [block] runs, or `null` if somebody else holds it.
     */
    public suspend fun <R> underLock(
        name: String,
        block: suspend () -> R,
    ): R?

    /**
     * Provisions any backing schema or resources needed by this backend. Default is a no-op.
     */
    public suspend fun setup() {}
}

public fun createKeyValueStore(
    persistence: Persistence,
    name: String,
): KeyValueStore = persistence.keyValueStore(name)

public fun createAppendLog(
    persistence: Persistence,
    name: String,
): AppendLog = persistence.appendLog(name)

public fun createWorkQueue(
    persistence: Persistence,
    name: String,
): WorkQueue = persistence.workQueue(name)
