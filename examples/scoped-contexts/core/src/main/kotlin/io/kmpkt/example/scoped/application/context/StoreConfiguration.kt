package io.kmpkt.example.scoped.application.context

import io.kmpkt.example.scoped.domain.model.StoreId

/** Runtime isolation parameters bound to an active [StoreContext] child container. */
data class StoreConfiguration(
    val storeId: StoreId,
    val erpEndpoint: String,
    val storageDirectory: String,
    val databaseSchema: String,
)
