package io.kmpkt.worker

import io.kmpkt.di.ApplicationRunner
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope

/**
 * Starts all registered [ManagedWorker] instances when the application boots and stops them on shutdown.
 */
public class WorkerRunner(
    private val workers: List<ManagedWorker>,
    private val onShutdown: ((Thread) -> Unit)? = Runtime.getRuntime()::addShutdownHook,
) : ApplicationRunner,
    AutoCloseable {
    override fun run(args: Array<String>) {
        if (workers.isEmpty()) return
        workers.forEach { it.start() }
        onShutdown?.invoke(Thread(::close, "kmpkt-worker-shutdown"))
    }

    override fun close() {
        runBlocking {
            supervisorScope {
                workers.forEach { launch { it.stop() } }
            }
        }
    }
}
