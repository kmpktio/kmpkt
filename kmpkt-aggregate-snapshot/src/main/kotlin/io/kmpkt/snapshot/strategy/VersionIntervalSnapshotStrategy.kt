package io.kmpkt.snapshot.strategy

/** Snapshots whenever the version crosses a multiple of [interval], regardless of how many events one save appends. */
public class VersionIntervalSnapshotStrategy(
    private val interval: Int = DEFAULT_INTERVAL,
) : SnapshotStrategy {
    override fun shouldSnapshot(
        previousVersion: Long,
        newVersion: Long,
    ): Boolean = previousVersion / interval < newVersion / interval

    public companion object {
        public const val DEFAULT_INTERVAL: Int = 50
    }
}
