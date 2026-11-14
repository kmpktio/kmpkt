package io.kmpkt.di.compiler.processor

import com.tschuchort.compiletesting.SourceFile

// Feature modules: plain Kotlin, no import of anything kmpkt.

internal val messenger =
    SourceFile.kotlin(
        "Messenger.kt",
        """
        package acme.messenger

        class DataSource(val url: String)

        data class RetryConfig(
            val maxAttempts: Int = 3,
            val retryOn: (Throwable) -> Boolean = { true },
        )

        interface MessageBus {
            fun dispatch(message: Any): String
        }

        class InMemoryMessageBus : MessageBus {
            override fun dispatch(message: Any) = "memory(${'$'}message)"
        }

        class JdbcOutboxMessageBus(private val dataSource: DataSource) : MessageBus {
            override fun dispatch(message: Any) = "outbox@${'$'}{dataSource.url}(${'$'}message)"
        }

        class RetryingMessageBus(
            private val inner: MessageBus,
            private val config: RetryConfig,
        ) : MessageBus {
            override fun dispatch(message: Any) =
                "retry*${'$'}{config.maxAttempts}:${'$'}{config.retryOn(IllegalStateException())}(${'$'}{inner.dispatch(message)})"
        }

        interface InvoiceRepository {
            fun describe(): String
        }

        internal class SecretAdapter(private val ds: DataSource) : InvoiceRepository {
            override fun describe() = "secret@${'$'}{ds.url}"
        }

        fun createSecretAdapter(ds: DataSource): InvoiceRepository = SecretAdapter(ds)
        """.trimIndent(),
    )

internal val kernel =
    SourceFile.kotlin(
        "Kernel.kt",
        """
        package acme.kernel

        import kotlin.reflect.KClass

        interface DomainEvent
        class InvoicePaid : DomainEvent
        class OrderShipped : DomainEvent

        interface DomainEventHandler<E : DomainEvent> {
            fun name(): String = this::class.simpleName!!
        }

        class EventDispatcher(private val handlers: Map<KClass<out DomainEvent>, List<DomainEventHandler<*>>>) {
            fun routes(): String =
                handlers.entries.joinToString("; ") { (event, handlers) ->
                    event.simpleName + " -> " + handlers.joinToString { it.name() }
                }
        }

        class WhenInvoicePaidReleaseShipment : DomainEventHandler<InvoicePaid>
        class WhenInvoicePaidNotifyWarehouse : DomainEventHandler<InvoicePaid>
        class WhenOrderShippedSendInvoice : DomainEventHandler<OrderShipped>
        class NeverReached
        """.trimIndent(),
    )

// Companion -di modules: they depend on their feature module and on kmpkt-di-compiler-api.

internal val messengerDi =
    SourceFile.kotlin(
        "MessengerComponent.kt",
        """
        package acme.messenger.di

        import acme.messenger.InMemoryMessageBus
        import acme.messenger.JdbcOutboxMessageBus
        import acme.messenger.MessageBus
        import acme.messenger.RetryConfig
        import acme.messenger.RetryingMessageBus
        import io.kmpkt.di.compiler.Component
        import io.kmpkt.di.compiler.ContainerBuilder

        enum class TransportType { IN_MEMORY, JDBC_OUTBOX }

        class MessengerOptions {
            var transport: TransportType = TransportType.IN_MEMORY
            var enableRetry: Boolean = false
        }

        class MessengerComponent : Component<MessengerOptions> {
            override fun defaults() = MessengerOptions()

            override fun ContainerBuilder.register(config: MessengerOptions) {
                when (config.transport) {
                    TransportType.IN_MEMORY -> service<InMemoryMessageBus> { bind<MessageBus>() }
                    TransportType.JDBC_OUTBOX -> service<JdbcOutboxMessageBus> { bind<MessageBus>() }
                }

                if (config.enableRetry) {
                    service<RetryConfig>()
                    service<RetryingMessageBus> {
                        decorates<MessageBus>(priority = 10)
                    }
                }
            }
        }
        """.trimIndent(),
    )

internal val kernelDi =
    SourceFile.kotlin(
        "KernelComponent.kt",
        """
        package acme.kernel.di

        import acme.kernel.DomainEventHandler
        import acme.kernel.EventDispatcher
        import io.kmpkt.di.ApplicationRunner
        import io.kmpkt.di.compiler.Argument
        import io.kmpkt.di.compiler.BuilderPass
        import io.kmpkt.di.compiler.Component
        import io.kmpkt.di.compiler.ConstValue
        import io.kmpkt.di.compiler.ContainerBuilder
        import io.kmpkt.di.compiler.ServiceReference
        import io.kmpkt.di.compiler.Tag
        import io.kmpkt.di.compiler.TypeArgument
        import io.kmpkt.di.compiler.typeRef

        data class EventHandlerTag(override val priority: Int = 0) : Tag

        /** Routes each handler by the E of its DomainEventHandler<E>. */
        class RouteEventHandlersPass : BuilderPass {
            override fun process(container: ContainerBuilder) {
                val routes =
                    container.tagged<EventHandlerTag>().groupBy { handler ->
                        container.types
                            .typeArguments(handler.definition.type, of = typeRef<DomainEventHandler<*>>())
                            .filterIsInstance<TypeArgument.Of>()
                            .single()
                            .type
                    }

                container.update<EventDispatcher> { dispatcher ->
                    dispatcher.copy(
                        arguments = dispatcher.arguments + (
                            "handlers" to Argument.Dictionary(
                                routes.map { (event, handlers) ->
                                    Argument.Literal(ConstValue.ClassLiteral(event)) to Argument.Sequence(
                                        items = handlers.map { Argument.Service(ServiceReference.ById(it.definition.id)) },
                                        elementType = typeRef<DomainEventHandler<*>>(),
                                    )
                                },
                            )
                        ),
                    )
                }
            }
        }

        class KernelComponent : Component<Unit> {
            override fun defaults() = Unit

            override fun ContainerBuilder.prepare() {
                autoconfigure<ApplicationRunner> {}
                autoconfigure<DomainEventHandler<*>> {
                    tag(EventHandlerTag())
                }
                addPass(RouteEventHandlersPass())
            }

            override fun ContainerBuilder.register(config: Unit) {
                service<EventDispatcher>()
            }
        }
        """.trimIndent(),
    )

// The application's `di` source set: compiled before the application itself and put on its `ksp` classpath.

internal val appConfig =
    SourceFile.kotlin(
        "AppConfig.kt",
        """
        package acme.app.di

        import acme.kernel.NeverReached
        import acme.kernel.WhenInvoicePaidNotifyWarehouse
        import acme.kernel.WhenInvoicePaidReleaseShipment
        import acme.kernel.WhenOrderShippedSendInvoice
        import acme.kernel.di.EventHandlerTag
        import acme.messenger.DataSource
        import acme.messenger.RetryConfig
        import acme.messenger.createSecretAdapter
        import acme.messenger.di.MessengerComponent
        import acme.messenger.di.TransportType
        import io.kmpkt.di.KmpKtApplication
        import io.kmpkt.di.compiler.Configuration
        import io.kmpkt.di.compiler.ContainerBuilder
        import io.kmpkt.di.compiler.literal

        class AppConfig : Configuration<KmpKtApplication.Default> {
            override fun ContainerBuilder.configure() {
                val container = this
                container.configure(MessengerComponent::class) {
                    transport = TransportType.JDBC_OUTBOX
                    enableRetry = true
                }

                container.service<DataSource> {
                    argument("url", literal("jdbc:test"))
                }
                container.service<RetryConfig> {
                    argument("maxAttempts", literal(5))
                }
                container.factory(::createSecretAdapter)
                container.service<WhenInvoicePaidReleaseShipment>()
                container.service<WhenInvoicePaidNotifyWarehouse> {
                    tag(EventHandlerTag(priority = 10))
                }
                container.service<WhenOrderShippedSendInvoice>()
                container.service<NeverReached>()
                container.discover("acme.app") {
                    services<io.kmpkt.di.ApplicationRunner>()
                }
            }
        }
        """.trimIndent(),
    )

internal val app =
    SourceFile.kotlin(
        "App.kt",
        """
        package acme.app

        import acme.kernel.EventDispatcher
        import acme.messenger.InvoiceRepository
        import acme.messenger.MessageBus
        import io.kmpkt.di.ApplicationRunner
        import io.kmpkt.di.KmpKt

        object Output {
            var report: List<String> = emptyList()
        }

        class AppRunner(
            private val bus: MessageBus,
            private val dispatcher: EventDispatcher,
            private val invoices: InvoiceRepository,
        ) : ApplicationRunner {
            override fun run(args: Array<String>) {
                Output.report =
                    listOf(bus.dispatch("hello"), dispatcher.routes(), invoices.describe(), (bus === bus).toString())
            }
        }

        fun run(): List<String> {
            KmpKt.run()
            return Output.report
        }
        """.trimIndent(),
    )
