package io.kmpkt.example.scoped.domain.model

/** Lifecycle events emitted when a store is linked to or unlinked from the gateway. */
sealed interface StoreEvent {
    val storeId: StoreId

    /** Signals that a store should be provisioned with an isolated runtime context. */
    data class StoreConnected(
        override val storeId: StoreId,
        val erpEndpoint: String,
    ) : StoreEvent

    /** Signals that a store's active context and background workers should be torn down. */
    data class StoreDisconnected(
        override val storeId: StoreId,
    ) : StoreEvent
}
