package io.kmpkt.example.scoped.application.context

import io.kmpkt.di.ContextHandle
import io.kmpkt.di.ContextScope
import io.kmpkt.example.scoped.domain.model.StoreEvent
import io.kmpkt.example.scoped.domain.model.StoreId
import io.kmpkt.example.scoped.domain.model.StoreNotFound
import java.util.concurrent.ConcurrentHashMap

/** Manages active [StoreContext] child containers driven by incoming [StoreEvent]s. */
class StoreRegistry(
    private val storeContext: ContextScope<StoreConfiguration, ContextHandle<StoreConfiguration>>,
) : AutoCloseable {
    private val activeContexts = ConcurrentHashMap<StoreId, ContextHandle<StoreConfiguration>>()

    fun onEvent(event: StoreEvent) {
        when (event) {
            is StoreEvent.StoreConnected -> {
                val config =
                    StoreConfiguration(
                        storeId = event.storeId,
                        erpEndpoint = event.erpEndpoint,
                        storageDirectory = "/var/data/stores/${event.storeId.value}",
                        databaseSchema = "store_${event.storeId.value}",
                    )

                activeContexts.compute(event.storeId) { _, existing ->
                    when {
                        // First connect: open an isolated child container and start eager scoped services.
                        existing == null -> storeContext.open(config)
                        // Idempotent replay with identical config: keep the warm caches and running worker.
                        existing.context == config -> existing
                        // Config changed (e.g. new ERP endpoint): close the stale handle before opening a fresh one.
                        else -> {
                            existing.close()
                            storeContext.open(config)
                        }
                    }
                }
            }

            is StoreEvent.StoreDisconnected -> {
                // Closing the handle stops all AutoCloseable services (e.g. CatalogSyncWorker) in reverse order.
                activeContexts
                    .remove(event.storeId)
                    ?.close()
            }
        }
    }

    fun get(storeId: StoreId): ContextHandle<StoreConfiguration> =
        activeContexts[storeId] ?: throw StoreNotFound(storeId)

    override fun close() {
        activeContexts.keys.toList().forEach { storeId ->
            activeContexts
                .remove(storeId)
                ?.close()
        }
    }
}
