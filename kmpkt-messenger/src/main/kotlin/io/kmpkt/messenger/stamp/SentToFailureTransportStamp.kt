package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Stamp

/**
 * Marks an envelope as having been moved to a failure transport from [originalTransportName].
 *
 * A message carrying this stamp is not sent to a failure transport a second time if it fails again
 * while being re-processed.
 */
public data class SentToFailureTransportStamp(
    public val originalTransportName: String,
) : Stamp
