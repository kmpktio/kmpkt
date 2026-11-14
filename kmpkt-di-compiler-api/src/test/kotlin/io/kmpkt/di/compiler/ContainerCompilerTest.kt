package io.kmpkt.di.compiler

import io.kmpkt.di.KmpKtApplication
import io.kmpkt.di.compiler.fixture.DataSource
import io.kmpkt.di.compiler.fixture.DomainEventHandler
import io.kmpkt.di.compiler.fixture.EventDispatcher
import io.kmpkt.di.compiler.fixture.EventHandlerTag
import io.kmpkt.di.compiler.fixture.Fixture
import io.kmpkt.di.compiler.fixture.InMemoryMessageBus
import io.kmpkt.di.compiler.fixture.JdbcOutboxMessageBus
import io.kmpkt.di.compiler.fixture.LoggingMessageBus
import io.kmpkt.di.compiler.fixture.MessageBus
import io.kmpkt.di.compiler.fixture.RetryConfig
import io.kmpkt.di.compiler.fixture.RetryingMessageBus
import io.kmpkt.di.compiler.fixture.TransportType
import io.kmpkt.di.compiler.fixture.WhenInvoicePaidNotifyWarehouse
import io.kmpkt.di.compiler.fixture.WhenInvoicePaidReleaseShipment
import io.kmpkt.di.compiler.fixture.WhenOrderShippedSendInvoice
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass

private class MessengerOptions {
    var transport: TransportType = TransportType.IN_MEMORY
    var enableRetry: Boolean = false
    val log = mutableListOf<String>()
}

private class MessengerComponent : Component<MessengerOptions> {
    override fun defaults() = MessengerOptions()

    override fun ContainerBuilder.register(config: MessengerOptions) {
        when (config.transport) {
            TransportType.IN_MEMORY -> service<InMemoryMessageBus> { bind<MessageBus>() }
            TransportType.JDBC_OUTBOX -> service<JdbcOutboxMessageBus> { bind<MessageBus>() }
        }

        if (config.enableRetry) {
            external<RetryConfig>()
            service<RetryingMessageBus> {
                decorates<MessageBus>(priority = 10)
            }
        }
    }
}

/** Remembers the options it was loaded with. */
private class RecordingComponent : Component<MessengerOptions> {
    override fun defaults() = MessengerOptions()

    override fun ContainerBuilder.register(config: MessengerOptions) {
        seen = config.log.toList()
    }

    companion object {
        var seen: List<String> = emptyList()
    }
}

private class PrependingComponent : Component<Unit> {
    override fun defaults() = Unit

    // The later prepend goes ahead of the earlier one: each is put in front of everything queued so far.
    override fun ContainerBuilder.prepare() {
        prepend<RecordingComponent, _> { log += "also prepended" }
        prepend(RecordingComponent::class) { log += "prepended" }
    }

    override fun ContainerBuilder.register(config: Unit) = Unit
}

/** Collects every tagged handler into the dispatcher. */
private class CollectHandlersPass : BuilderPass {
    override fun process(container: ContainerBuilder) {
        container.update<EventDispatcher> { it.copy(arguments = it.arguments + ("handlers" to tagged<EventHandlerTag>())) }
    }
}

private class KernelComponent : SimpleComponent {
    override fun ContainerBuilder.prepare() {
        autoconfigure<DomainEventHandler<*>> {
            tag(EventHandlerTag())
        }
        addPass(CollectHandlersPass())
    }
}

private fun Fixture.exposeBus() = container.entryPoint("bus", ServiceKey(typeRef<MessageBus>()))

private fun byId(id: ServiceId): Argument = Argument.Service(ServiceReference.ById(id))

class ContainerCompilerTest :
    FunSpec({
        test("a component is loaded with its defaults when nothing configured it") {
            // Arrange
            val fixture = Fixture().apply { exposeBus() }

            // Act
            val compiled = ContainerCompiler(components = listOf(MessengerComponent())).compile(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            compiled shouldBe
                CompiledContainer(
                    definitions =
                        listOf(
                            ServiceDefinition(
                                id = ServiceId.of<InMemoryMessageBus>(),
                                type = typeRef<InMemoryMessageBus>(),
                                source = MessengerComponent::class.qualifiedName,
                            ),
                        ),
                    entryPoints = listOf(ResolvedEntryPoint("bus", typeRef<MessageBus>(), ServiceId.of<InMemoryMessageBus>())),
                )
        }

        test("options from a configuration decide what the component registers, down to the decorated, inlined graph") {
            // Arrange
            val fixture = Fixture().apply { exposeBus() }
            val configuration =
                Configuration<KmpKtApplication.Default> {
                    val container = this
                    container.external<DataSource>()
                    container.configure(MessengerComponent::class) {
                        transport = TransportType.JDBC_OUTBOX
                        enableRetry = true
                    }
                }

            // Act
            val compiled = ContainerCompiler(listOf(MessengerComponent()), listOf(configuration)).compile(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            compiled?.entryPoints shouldBe listOf(ResolvedEntryPoint("bus", typeRef<MessageBus>(), ServiceId.of<RetryingMessageBus>()))
            compiled?.definitions?.associate { it.id to Triple(it.arguments, it.inlined, it.source) } shouldBe
                mapOf(
                    ServiceId.of<DataSource>() to Triple(emptyMap(), false, null),
                    ServiceId.of<JdbcOutboxMessageBus>() to
                        Triple(
                            mapOf("dataSource" to byId(ServiceId.of<DataSource>())),
                            true,
                            MessengerComponent::class.qualifiedName,
                        ),
                    ServiceId.of<RetryConfig>() to Triple(emptyMap(), false, MessengerComponent::class.qualifiedName),
                    ServiceId.of<RetryingMessageBus>() to
                        Triple(
                            mapOf(
                                "inner" to byId(ServiceId.of<JdbcOutboxMessageBus>()),
                                "config" to byId(ServiceId.of<RetryConfig>()),
                            ),
                            false,
                            MessengerComponent::class.qualifiedName,
                        ),
                )
        }

        test("option blocks are applied in the order queued, prepended ones first") {
            // Arrange
            val fixture = Fixture()
            val configuration =
                Configuration<KmpKtApplication.Default> {
                    val container = this
                    container.configure(RecordingComponent::class) { log += "first" }
                    container.configure<RecordingComponent, _> { log += "second" }
                }

            // Act
            ContainerCompiler(listOf(RecordingComponent(), PrependingComponent()), listOf(configuration)).compile(fixture.container)

            // Assert
            RecordingComponent.seen shouldContainExactly listOf("prepended", "also prepended", "first", "second")
        }

        test("what the application registered itself wins over what a component registers") {
            // Arrange
            val fixture = Fixture().apply { exposeBus() }
            val configuration =
                Configuration<KmpKtApplication.Default> {
                    val container = this
                    container.service<LoggingMessageBus>(ServiceId.of<InMemoryMessageBus>()).bind<MessageBus>()
                    container.service<InMemoryMessageBus>(ServiceId("inner"))
                    container.update(ServiceId.of<InMemoryMessageBus>()) {
                        it.copy(arguments = mapOf("inner" to byId(ServiceId("inner"))))
                    }
                }

            // Act
            val compiled = ContainerCompiler(listOf(MessengerComponent()), listOf(configuration)).compile(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            compiled?.definition(ServiceId.of<InMemoryMessageBus>())?.type shouldBe typeRef<LoggingMessageBus>()
            compiled?.definition(ServiceId.of<InMemoryMessageBus>())?.source.shouldBeNull()
        }

        test("a key the application bound itself stays bound to its service when a component binds it too") {
            // Arrange
            val fixture = Fixture().apply { exposeBus() }
            val configuration =
                Configuration<KmpKtApplication.Default> {
                    val container = this
                    container.factory<MessageBus>("io.kmpkt.di.compiler.fixture", "createMessageBus", ServiceId("own"))
                }

            // Act
            val compiled = ContainerCompiler(listOf(MessengerComponent()), listOf(configuration)).compile(fixture.container)

            // Assert
            compiled?.entryPoints?.single()?.id shouldBe ServiceId("own")
        }

        test("a component loads into a builder of its own and does not see the others' definitions") {
            // Arrange
            val fixture = Fixture()
            var seen: Set<ServiceId>? = null
            val peeking =
                object : Component<Unit> {
                    override fun defaults() = Unit

                    override fun ContainerBuilder.register(config: Unit) {
                        seen = definitions.keys.toSet()
                    }
                }

            // Act
            ContainerCompiler(listOf(MessengerComponent(), peeking)).compile(fixture.container)

            // Assert
            seen shouldBe emptySet()
        }

        test("autoconfiguration, a component's pass and tagged injection work together") {
            // Arrange
            val fixture = Fixture()
            fixture.container.entryPoint("dispatcher", ServiceKey(typeRef<EventDispatcher>()))
            val configuration =
                Configuration<KmpKtApplication.Default> {
                    val container = this
                    container.service<EventDispatcher>()
                    container.service<WhenInvoicePaidReleaseShipment>()
                    container.service<WhenInvoicePaidNotifyWarehouse>().tag(EventHandlerTag(10))
                    container.service<WhenOrderShippedSendInvoice>()
                }

            // Act
            val compiled = ContainerCompiler(listOf(KernelComponent()), listOf(configuration)).compile(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            compiled?.definition(ServiceId.of<EventDispatcher>())?.arguments shouldBe
                mapOf(
                    "handlers" to
                        Argument.Sequence(
                            listOf(
                                byId(ServiceId.of<WhenInvoicePaidNotifyWarehouse>()),
                                byId(ServiceId.of<WhenInvoicePaidReleaseShipment>()),
                                byId(ServiceId.of<WhenOrderShippedSendInvoice>()),
                            ),
                            elementType = typeRef<DomainEventHandler<*>>(),
                        ),
                )
            compiled?.definitions?.map { it.id } shouldContainExactly
                listOf(
                    ServiceId.of<EventDispatcher>(),
                    ServiceId.of<WhenInvoicePaidReleaseShipment>(),
                    ServiceId.of<WhenInvoicePaidNotifyWarehouse>(),
                    ServiceId.of<WhenOrderShippedSendInvoice>(),
                )
        }

        test("passes run phase by phase, highest priority first, and in the order added among equals") {
            // Arrange
            val fixture = Fixture()
            val ran = mutableListOf<String>()
            val component =
                object : SimpleComponent {
                    override fun ContainerBuilder.prepare() {
                        addPass({ ran += "afterRemoving" }, PassPhase.AfterRemoving)
                        addPass({ ran += "removing: ${it.definitions.size} left" }, PassPhase.Removing, priority = -1)
                        addPass({ ran += "beforeRemoving" }, PassPhase.BeforeRemoving)
                        addPass({ ran += "optimization, late" }, PassPhase.Optimization, priority = -1)
                        addPass({ ran += "default, first added" })
                        addPass({ ran += "default, second added" })
                        addPass({ ran += "default, high priority" }, priority = 5)
                        addPass({ ran += "before autoconfiguration" }, priority = 101)
                        service<InMemoryMessageBus>()
                    }
                }

            // Act
            ContainerCompiler(listOf(component)).compile(fixture.container)

            // Assert
            ran shouldContainExactly
                listOf(
                    "before autoconfiguration",
                    "default, high priority",
                    "default, first added",
                    "default, second added",
                    "optimization, late",
                    "beforeRemoving",
                    "removing: 0 left",
                    "afterRemoving",
                )
        }

        test("the pipeline stops after the first pass that reported an error, and yields nothing") {
            // Arrange
            val fixture = Fixture()
            val ran = mutableListOf<String>()
            val component =
                object : SimpleComponent {
                    override fun ContainerBuilder.prepare() {
                        addPass({
                            it.diagnostics.error("first")
                            it.diagnostics.error("second")
                        })
                        addPass({ ran += "later pass" }, PassPhase.AfterRemoving)
                    }
                }

            // Act
            val compiled = ContainerCompiler(listOf(component)).compile(fixture.container)

            // Assert
            compiled.shouldBeNull()
            ran.shouldBeEmpty()
            fixture.diagnostics.errors shouldContainExactly listOf("first", "second")
        }

        test("an error in the very last pass still yields nothing") {
            // Arrange
            val fixture = Fixture()
            val component =
                object : SimpleComponent {
                    override fun ContainerBuilder.prepare() {
                        addPass({ it.diagnostics.error("late") }, PassPhase.AfterRemoving)
                    }
                }

            // Act
            val compiled = ContainerCompiler(listOf(component)).compile(fixture.container)

            // Assert
            compiled.shouldBeNull()
        }

        test("an unresolvable graph is reported in full by the pass that found it") {
            // Arrange
            val fixture = Fixture().apply { exposeBus() }
            val configuration =
                Configuration<KmpKtApplication.Default> {
                    val container = this
                    service<JdbcOutboxMessageBus>().bind<MessageBus>()
                    service<RetryingMessageBus>()
                }

            // Act
            val compiled = ContainerCompiler(configurations = listOf(configuration)).compile(fixture.container)

            // Assert
            compiled.shouldBeNull()
            fixture.diagnostics.errors.map { it.substringBefore(":") } shouldContainExactly
                listOf(
                    "Cannot autowire parameter \"dataSource\" of \"io.kmpkt.di.compiler.fixture.JdbcOutboxMessageBus\"",
                    "Cannot autowire parameter \"config\" of \"io.kmpkt.di.compiler.fixture.RetryingMessageBus\"",
                )
        }

        test("a component runs after the ones it requires, in every step, whatever order they were given in") {
            // Arrange
            val fixture = Fixture()
            val ran = mutableListOf<String>()

            // Act
            ContainerCompiler(listOf(Top(ran), Middle(ran), Base(ran))).compile(fixture.container)

            // Assert
            ran shouldContainExactly
                listOf(
                    "prepare Base",
                    "prepare Middle",
                    "prepare Top",
                    "register Base",
                    "register Middle",
                    "register Top",
                )
        }

        test("a component whose requirement is not there fails the compilation, naming both") {
            // Arrange
            val fixture = Fixture()
            val ran = mutableListOf<String>()

            // Act
            val compiled = ContainerCompiler(listOf(Top(ran), Base(ran))).compile(fixture.container)

            // Assert
            compiled.shouldBeNull()
            ran.shouldBeEmpty()
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "io.kmpkt.di.compiler.Top requires io.kmpkt.di.compiler.Middle, which is not among the components. " +
                        "Put the module that declares it on the processor's classpath - ksp(project(\":…-di\")).",
                )
        }

        test("components that require each other in a circle fail the compilation with the circle") {
            // Arrange
            val fixture = Fixture()

            // Act
            val compiled = ContainerCompiler(listOf(Chicken(), Egg())).compile(fixture.container)

            // Assert
            compiled.shouldBeNull()
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "Components require each other in a circle: io.kmpkt.di.compiler.Chicken -> io.kmpkt.di.compiler.Egg -> " +
                        "io.kmpkt.di.compiler.Chicken.",
                )
        }
    })

/** Says when each of its steps ran. */
private abstract class Recording(
    private val ran: MutableList<String>,
) : SimpleComponent {
    private val name = this::class.simpleName

    override fun ContainerBuilder.prepare() {
        ran += "prepare $name"
    }

    override fun ContainerBuilder.register() {
        ran += "register $name"
    }
}

private class Base(
    ran: MutableList<String>,
) : Recording(ran)

private class Middle(
    ran: MutableList<String>,
) : Recording(ran) {
    override val requires: Set<KClass<out Component<*>>> = setOf(Base::class)
}

private class Top(
    ran: MutableList<String>,
) : Recording(ran) {
    override val requires: Set<KClass<out Component<*>>> = setOf(Middle::class, Base::class)
}

private class Chicken : SimpleComponent {
    override val requires: Set<KClass<out Component<*>>> = setOf(Egg::class)
}

private class Egg : SimpleComponent {
    override val requires: Set<KClass<out Component<*>>> = setOf(Chicken::class)
}
