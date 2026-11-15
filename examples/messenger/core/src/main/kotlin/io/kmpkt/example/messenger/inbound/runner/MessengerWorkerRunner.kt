package io.kmpkt.example.messenger.inbound.runner

import io.kmpkt.di.ApplicationRunner
import io.kmpkt.worker.ManagedWorker

/** Starts all background message workers like a backend service before any interaction arrives. */
class MessengerWorkerRunner(
    private val workers: List<ManagedWorker>,
) : ApplicationRunner {
    override fun run(args: Array<String>) {
        workers.forEach { it.start() }
    }
}
