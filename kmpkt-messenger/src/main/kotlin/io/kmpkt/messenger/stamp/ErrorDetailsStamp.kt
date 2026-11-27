package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Stamp

/**
 * Records the failure that caused a retry or dead-lettering using KMP-safe class names (`simpleName`).
 */
public data class ErrorDetailsStamp(
    public val exceptionClass: String,
    public val exceptionMessage: String?,
) : Stamp {
    public companion object {
        public fun from(failure: Throwable): ErrorDetailsStamp =
            ErrorDetailsStamp(
                exceptionClass = failure::class.simpleName ?: "Unknown",
                exceptionMessage = failure.message,
            )
    }
}
