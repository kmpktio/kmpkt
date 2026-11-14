package io.kmpkt.messenger.message

/** A [message] in transit through the bus, together with whatever [stamps] got attached to it along the way. */
public data class Envelope(
    public val message: Message,
    public val stamps: Set<Stamp> = emptySet(),
) {
    public fun with(stamp: Stamp): Envelope = copy(stamps = stamps + stamp)

    public fun with(vararg stamps: Stamp): Envelope = copy(stamps = this.stamps + stamps)

    public inline fun <reified T : Stamp> all(): List<T> = stamps.filterIsInstance<T>()

    public inline fun <reified T : Stamp> last(): T? = all<T>().lastOrNull()
}
