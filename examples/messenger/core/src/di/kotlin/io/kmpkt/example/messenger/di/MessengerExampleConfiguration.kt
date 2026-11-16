package io.kmpkt.example.messenger.di

import io.kmpkt.di.compiler.ApplicationRunnerTag
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.example.messenger.MessengerApp
import io.kmpkt.example.messenger.application.handler.AnalyticsOrderPlacedHandler
import io.kmpkt.example.messenger.application.handler.AuditOrderPlacedHandler
import io.kmpkt.example.messenger.application.handler.PlaceOrderHandler
import io.kmpkt.example.messenger.application.handler.SendReceiptHandler
import io.kmpkt.example.messenger.domain.command.SendReceipt
import io.kmpkt.example.messenger.inbound.bus.CorrelationMiddleware
import io.kmpkt.example.messenger.inbound.runner.DemoInteractionRunner
import io.kmpkt.example.messenger.inbound.runner.MessengerWorkerRunner
import io.kmpkt.example.messenger.outbound.transport.ObservableInMemoryTransport
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.di.MessageHandlerTag
import io.kmpkt.messenger.di.delay
import io.kmpkt.messenger.di.maxRetries
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.retry.MultiplierRetryStrategy
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.messenger.transport.SyncTransport
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.messenger.worker.TransportWork
import io.kmpkt.worker.di.ManagedWorkerTag
import kotlin.time.Duration.Companion.milliseconds
import io.kmpkt.di.compiler.tagged as taggedArgument

class MessengerExampleConfiguration : Configuration<MessengerApp> {
    override fun ContainerBuilder.configure() {
        // Scan package so handlers, middleware, ObservableLogger, SimulatedReceiptMailer, and runners are registered.
        discover("io.kmpkt.example.messenger")

        messenger {
            // 1. Transports: dead-letter records failed messages in ObservableLogger; async-jobs buffers queued work.
            val deadLetter = transport<ObservableInMemoryTransport>("dead-letter")
            val asyncJobs =
                transport<InMemoryTransport>("async-jobs") {
                    retry<MultiplierRetryStrategy> {
                        maxRetries = 1
                        delay = 10.milliseconds
                    }
                    failureTransport = deadLetter
                }
            val sync = transport<SyncTransport>("sync")

            // 2. Commands bus: single handler per message, correlation middleware, SendReceipt routed asynchronously.
            defaultBus =
                bus("commands") {
                    defaultTransport = sync
                    middleware<CorrelationMiddleware>()
                    route<SendReceipt>(asyncJobs)
                }

            // 3. Events bus: multiple listeners per event, delivered synchronously.
            bus("events") {
                policy = HandlerPolicy.MULTIPLE
                defaultTransport = sync
            }
        }

        // 4. Bind each handler to its bus; higher priority runs first on multi-handler buses.
        service<PlaceOrderHandler> {
            tag(MessageHandlerTag(bus = "commands"))
            argument("events", byKey<MessageBus>("events"))
        }
        service<SendReceiptHandler> { tag(MessageHandlerTag(bus = "commands")) }
        service<AuditOrderPlacedHandler> { tag(MessageHandlerTag(bus = "events", priority = 10)) }
        service<AnalyticsOrderPlacedHandler> { tag(MessageHandlerTag(bus = "events")) }

        // 5. Background worker for "async-jobs": WorkerComponent automatically wraps this Work into a ManagedWorker.
        service<TransportWork>(ServiceId("async-jobs")) {
            argument("transport", byKey<Transport>("async-jobs"))
            argument("transportName", literal("async-jobs"))
            argument("messageBus", byKey<MessageBus>("commands"))
            argument("failureTransport", byKey<Transport>("dead-letter"))
        }

        // 6. Start background workers first (priority 100), then run the demo client interaction (priority 0).
        service<MessengerWorkerRunner> {
            tag(ApplicationRunnerTag(priority = 100))
            argument("workers", taggedArgument<ManagedWorkerTag>())
        }

        service<DemoInteractionRunner> {
            argument("commands", byKey<MessageBus>("commands"))
        }
    }
}

private inline fun <reified T : Any> byKey(qualifier: String): Argument =
    Argument.Service(ServiceReference.ByKey(ServiceKey(typeRef<T>(), qualifier)))
