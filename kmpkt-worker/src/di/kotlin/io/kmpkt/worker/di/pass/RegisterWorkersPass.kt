package io.kmpkt.worker.di.pass

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.literal
import io.kmpkt.worker.ManagedWorker
import io.kmpkt.worker.Worker
import io.kmpkt.worker.createManagedWorker
import io.kmpkt.worker.di.ManagedWorkerTag
import io.kmpkt.worker.di.WorkTag

/**
 * Registers a [ManagedWorker] (bound as both [Worker] and [ManagedWorker]) for every discovered [io.kmpkt.worker.Work] service.
 */
public class RegisterWorkersPass : BuilderPass {
    override fun process(container: ContainerBuilder) {
        container.tagged<WorkTag>().forEach { tagged ->
            val workId = tagged.definition.id
            val workerName = workId.value.substringAfterLast('.')
            container.factory(::createManagedWorker, ServiceId("worker.$workerName")) {
                tag(ManagedWorkerTag)
                bind<Worker>(workerName)
                bind<ManagedWorker>(workerName)
                argument("name", literal(workerName))
                argument("work", Argument.Service(ServiceReference.ById(workId)))
            }
        }
    }
}
