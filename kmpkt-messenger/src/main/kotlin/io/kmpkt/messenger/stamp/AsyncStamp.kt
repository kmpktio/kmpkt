package io.kmpkt.messenger.stamp

import io.kmpkt.messenger.message.Stamp

/** Marks a message as wanting async processing - eligible to be sent to a queue/transport instead of handled inline. */
public data object AsyncStamp : Stamp
