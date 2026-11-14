package io.kmpkt.worker.di

import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.di.compiler.Tag
import io.kmpkt.worker.Work
import io.kmpkt.worker.di.pass.RegisterWorkersPass

public object WorkTag : Tag

public object ManagedWorkerTag : Tag

public class WorkerComponent : SimpleComponent {
    override fun ContainerBuilder.prepare() {
        autoconfigure<Work> { tag(WorkTag) }
    }

    override fun ContainerBuilder.register() {
        addPass(RegisterWorkersPass(), priority = -10)
    }
}
