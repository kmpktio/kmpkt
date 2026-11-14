package io.kmpkt.persistence.di

import io.kmpkt.di.compiler.ContainerBuilder

public class PersistenceOptions {
    internal val keyValues = linkedSetOf<String>()
    internal val appendLogs = linkedSetOf<String>()
    internal val workQueues = linkedSetOf<String>()

    public fun keyValue(name: String) {
        keyValues += name
    }

    public fun appendLog(name: String) {
        appendLogs += name
    }

    public fun workQueue(name: String) {
        workQueues += name
    }
}

public fun ContainerBuilder.persistence(block: PersistenceOptions.() -> Unit) {
    configure(PersistenceComponent::class, block)
}
