package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Stamp

/** This envelope came in from [transportName] - stops it from being routed back out to a transport again. */
public data class ReceivedStamp(
    public val transportName: String,
) : Stamp
