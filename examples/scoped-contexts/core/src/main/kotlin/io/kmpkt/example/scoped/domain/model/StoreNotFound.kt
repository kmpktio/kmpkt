package io.kmpkt.example.scoped.domain.model

/** Thrown when a request targets a store that has no active [StoreContext] in the registry. */
class StoreNotFound(
    val storeId: StoreId,
) : IllegalStateException("Store ${storeId.value} is not connected")
