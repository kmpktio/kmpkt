package io.kmpkt.messenger.outbox.relay.di

import io.kmpkt.di.compiler.ContainerDsl
import io.kmpkt.messenger.di.TransportRef

@ContainerDsl
public class OutboxRelayOptions {
    internal val relays: MutableList<RelayDeclaration> = mutableListOf()

    /**
     * Declares an outbox relay middleware forwarding envelopes received from [from] to [to].
     */
    public fun relay(
        from: TransportRef<*>,
        to: TransportRef<*>,
    ) {
        relays += RelayDeclaration(from = from, to = to)
    }
}

internal data class RelayDeclaration(
    val from: TransportRef<*>,
    val to: TransportRef<*>,
)
