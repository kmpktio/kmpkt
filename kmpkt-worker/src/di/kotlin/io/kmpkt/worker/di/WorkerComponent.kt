package io.kmpkt.worker.di

import io.kmpkt.di.compiler.ApplicationRunnerTag
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ContainerDsl
import io.kmpkt.di.compiler.Tag
import io.kmpkt.worker.Work
import io.kmpkt.worker.WorkerRunner
import io.kmpkt.worker.di.pass.RegisterWorkersPass
import io.kmpkt.di.compiler.tagged as taggedArgument

public object WorkTag : Tag

public object ManagedWorkerTag : Tag

@ContainerDsl
public class WorkerOptions {
    /** Starts all [io.kmpkt.worker.ManagedWorker] instances automatically when `KmpKt.run` runs. */
    public var autoStart: Boolean = false
}

public fun ContainerBuilder.workers(block: WorkerOptions.() -> Unit = {}) {
    configure(WorkerComponent::class, block)
}

public class WorkerComponent : Component<WorkerOptions> {
    override fun defaults(): WorkerOptions = WorkerOptions()

    override fun ContainerBuilder.prepare() {
        autoconfigure<Work> { tag(WorkTag) }
    }

    override fun ContainerBuilder.register(config: WorkerOptions) {
        addPass(RegisterWorkersPass(), priority = -10)
        if (config.autoStart) {
            service<WorkerRunner> {
                tag(ApplicationRunnerTag(priority = 100))
                argument("workers", taggedArgument<ManagedWorkerTag>())
            }
        }
    }
}
