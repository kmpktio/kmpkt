package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Stamp

/**
 * This message was given up on and put aside. [originalTransportName] is where it failed; [failure] and
 * [failureMessage] say with what, as text, so that the stamp survives a transport that serialises.
 *
 * A message carrying this stamp is not put aside a second time when it fails again while being looked at.
 */
public data class SentToFailureTransportStamp(
    public val originalTransportName: String,
    public val failure: String,
    public val failureMessage: String?,
) : Stamp
