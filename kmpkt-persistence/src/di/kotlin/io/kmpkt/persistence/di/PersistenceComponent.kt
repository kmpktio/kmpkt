package io.kmpkt.persistence.di

import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.literal
import io.kmpkt.persistence.AppendLog
import io.kmpkt.persistence.InMemoryPersistence
import io.kmpkt.persistence.KeyValueStore
import io.kmpkt.persistence.Persistence
import io.kmpkt.persistence.WorkQueue
import io.kmpkt.persistence.createAppendLog
import io.kmpkt.persistence.createKeyValueStore
import io.kmpkt.persistence.createWorkQueue

public class PersistenceComponent : Component<PersistenceOptions> {
    override fun defaults(): PersistenceOptions = PersistenceOptions()

    override fun ContainerBuilder.register(config: PersistenceOptions) {
        service<InMemoryPersistence> { bind<Persistence>() }

        config.keyValues.forEach { name ->
            factory(::createKeyValueStore, ServiceId("persistence.keyValue.$name")) {
                bind<KeyValueStore>(name)
                argument("name", literal(name))
            }
        }

        config.appendLogs.forEach { name ->
            factory(::createAppendLog, ServiceId("persistence.appendLog.$name")) {
                bind<AppendLog>(name)
                argument("name", literal(name))
            }
        }

        config.workQueues.forEach { name ->
            factory(::createWorkQueue, ServiceId("persistence.workQueue.$name")) {
                bind<WorkQueue>(name)
                argument("name", literal(name))
            }
        }
    }
}
