package io.kmpkt.messenger.persistence.di.pass

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.persistence.transport.PersistentTransport
import io.kmpkt.persistence.WorkQueue
import io.kmpkt.persistence.createWorkQueue

/**
 * Ensures every [PersistentTransport] registered via `messenger { transport<PersistentTransport>(name) }`
 * receives a dedicated [WorkQueue] scoped to `name`.
 */
public class WirePersistentTransportsPass : BuilderPass {
    override fun process(container: ContainerBuilder) {
        val transportType = typeRef<PersistentTransport>()
        val transports =
            container.definitions.values
                .filter { it.type == transportType && "workQueue" !in it.arguments }
                .toList()

        transports.forEach { definition ->
            val queueName = definition.id.value.removePrefix("messenger.transport.")
            val queueKey = ServiceKey(typeRef<WorkQueue>(), queueName)

            if (queueKey !in container.aliases) {
                container.factory(::createWorkQueue, ServiceId("persistence.workQueue.$queueName")) {
                    bind<WorkQueue>(queueName)
                    argument("name", literal(queueName))
                }
            }

            container.update(definition.id) { current ->
                current.copy(arguments = current.arguments + ("workQueue" to Argument.Service(ServiceReference.ByKey(queueKey))))
            }
        }
    }
}
