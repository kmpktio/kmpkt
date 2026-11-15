package io.kmpkt.event.store.di

import io.kmpkt.di.compiler.ContainerBuilder

public class EventStoreOptions {
    public var logName: String = "events"
}

public fun ContainerBuilder.eventStore(block: EventStoreOptions.() -> Unit = {}) {
    configure(EventStoreComponent::class, block)
}
