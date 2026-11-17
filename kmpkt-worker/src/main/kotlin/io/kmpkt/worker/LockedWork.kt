package io.kmpkt.worker

/**
 * [Work] decorator that acquires [lockName] via [lock] before running [delegate], returning `0`
 * (nothing handled) when the lock is currently held by another instance.
 */
public class LockedWork(
    private val delegate: Work,
    private val lock: WorkLock,
    private val lockName: String,
) : Work {
    override suspend fun run(): Int = lock.underLock(lockName) { delegate.run() } ?: 0
}
