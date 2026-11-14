package io.kmpkt.messenger.transport.stamp

import io.kmpkt.messenger.message.Stamp
import java.time.Instant

/** The envelope was sent to [transportName] at [sentAt], instead of being handled inline. */
public data class SentStamp(
    public val transportName: String,
    public val sentAt: Instant,
) : Stamp
