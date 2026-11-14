package io.kmpkt.messenger.transport

/** Builds a [T]-typed [Transport] from its [C]-typed configuration (e.g. a connection string, a channel capacity). */
public fun interface TransportFactory<T : Transport, C> {
    public fun create(config: C): T
}
