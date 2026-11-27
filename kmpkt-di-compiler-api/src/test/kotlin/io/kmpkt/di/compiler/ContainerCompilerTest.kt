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

        test("compiles scoped contexts with module views, parent fallback, and root ContextScope") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<RootSharedService>()
            fixture.types.declare<ScopedInput>()
            fixture.types.declare<ScopedDomain>(
                io.kmpkt.di.compiler.fixture
                    .parameter<ScopedInput>("input"),
            )
            fixture.types.declare<ScopedFeatureService>(
                io.kmpkt.di.compiler.fixture
                    .parameter<ScopedDomain>("domain"),
                io.kmpkt.di.compiler.fixture
                    .parameter<RootSharedService>("root"),
            )
            fixture.types.declare<ScopedHandle>(
                supertypes = listOf(TypeRef("io.kmpkt.di.ContextHandle", listOf(TypeArgument.Of(typeRef<ScopedDomain>())))),
            )
            fixture.types.declareProperties<ScopedView>(
                io.kmpkt.di.compiler.fixture
                    .parameter<ScopedFeatureService>("feature"),
            )
            fixture.types.declare<ScopedContextFactory>(
                supertypes =
                    listOf(
                        TypeRef(
                            "io.kmpkt.di.ContextFactory",
                            listOf(TypeArgument.Of(typeRef<ScopedDomain>()), TypeArgument.Of(typeRef<ScopedInput>())),
                        ),
                    ),
            )
            fixture.container.service<RootSharedService>()
            fixture.container.service<ScopedContextFactory>()

            val featureComponent =
                object : SimpleComponent {
                    override fun ContainerBuilder.register() {
                        context<TestStoreContext> {
                            service<ScopedFeatureService>()
                            view<ScopedView>()
                        }
                    }
                }
            val config =
                Configuration<KmpKtApplication.Default> {
                    context<TestStoreContext, ScopedHandle, ScopedInput>()
                    entryPoint<io.kmpkt.di.ContextScope<ScopedInput, ScopedHandle>>("stores")
                }

            // Act
            val compiled = ContainerCompiler(listOf(featureComponent), listOf(config)).compile(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            val ctx = compiled?.contexts?.single()
            ctx?.name shouldBe typeRef<TestStoreContext>().qualifiedName
            ctx?.views shouldContainExactly listOf(typeRef<ScopedView>())
            ctx?.compiled?.entryPoints?.map { it.name } shouldContainExactly listOf("context", "feature")
            val featureDef = ctx?.compiled?.definition(ServiceId.of<ScopedFeatureService>())
            featureDef?.arguments?.keys?.toList() shouldContainExactly listOf("domain", "root")
            compiled?.definitions?.any { it.instantiation is Instantiation.ContextScope } shouldBe true
        }

        test("extending an undeclared context fails compilation") {
            // Arrange
            val fixture = Fixture()
            val component =
                object : SimpleComponent {
                    override fun ContainerBuilder.register() {
                        context<TestStoreContext> {
                            service<InMemoryMessageBus>()
                        }
                    }
                }

            // Act
            val compiled = ContainerCompiler(listOf(component)).compile(fixture.container)

            // Assert
            compiled.shouldBeNull()
            fixture.diagnostics.errors.any { "extended without being declared" in it } shouldBe true
        }

        test("an unresolvable service inside a scoped context fails compilation") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<ScopedInput>()
            fixture.types.declare<ScopedDomain>()
            fixture.types.declare<ScopedHandle>(
                supertypes = listOf(TypeRef("io.kmpkt.di.ContextHandle", listOf(TypeArgument.Of(typeRef<ScopedDomain>())))),
            )
            fixture.types.declare<JdbcOutboxMessageBus>(
                io.kmpkt.di.compiler.fixture
                    .parameter<DataSource>("dataSource"),
            )
            fixture.types.declare<ScopedContextFactory>(
                supertypes =
                    listOf(
                        TypeRef(
                            "io.kmpkt.di.ContextFactory",
                            listOf(TypeArgument.Of(typeRef<ScopedDomain>()), TypeArgument.Of(typeRef<ScopedInput>())),
                        ),
                    ),
            )
            fixture.container.service<ScopedContextFactory>()

            val config =
                Configuration<KmpKtApplication.Default> {
                    context<TestStoreContext, ScopedHandle, ScopedInput> {
                        service<JdbcOutboxMessageBus>()
                        entryPoint<JdbcOutboxMessageBus>("bus")
                    }
                    entryPoint<io.kmpkt.di.ContextScope<ScopedInput, ScopedHandle>>("stores")
                }

            // Act
            val compiled = ContainerCompiler(configurations = listOf(config)).compile(fixture.container)

            // Assert
            compiled.shouldBeNull()
            fixture.diagnostics.hasErrors shouldBe true
        }

        test("exposes root services directly on scoped context views") {
            val fixture = Fixture()
            fixture.types.declare<RootSharedService>()
            fixture.types.declare<ScopedDomain>()
            fixture.types.declareProperties<RootOnlyView>(
                io.kmpkt.di.compiler.fixture
                    .parameter<RootSharedService>("root"),
            )
            fixture.container.service<RootSharedService>()

            val config =
                Configuration<KmpKtApplication.Default> {
                    context<TestStoreContext, ScopedDomain> {
                        view<RootOnlyView>()
                    }
                    entryPoint<io.kmpkt.di.ContextScope<ScopedDomain, io.kmpkt.di.ContextHandle<ScopedDomain>>>("stores")
                }

            val compiled = ContainerCompiler(configurations = listOf(config)).compile(fixture.container)

            fixture.diagnostics.errors.shouldBeEmpty()
            val ctx = compiled?.contexts?.single()
            ctx?.compiled?.entryPoints?.map { it.name } shouldContainExactly listOf("context", "root")
        }

        test("runs child passes and inherited autoconfigurations inside scoped contexts") {
            val fixture = Fixture()
            fixture.types.declare<ScopedDomain>()
            fixture.types.declareProperties<DispatcherView>(
                io.kmpkt.di.compiler.fixture
                    .parameter<EventDispatcher>("dispatcher"),
            )
            fixture.container.autoconfigure<DomainEventHandler<*>> {
                tag(EventHandlerTag())
            }

            var childPassRan = false
            val config =
                Configuration<KmpKtApplication.Default> {
                    context<TestStoreContext, ScopedDomain> {
                        service<WhenInvoicePaidReleaseShipment>()
                        service<EventDispatcher> {
                            argument<List<DomainEventHandler<*>>>(
                                io.kmpkt.di.compiler
                                    .tagged<EventHandlerTag>(),
                            )
                        }
                        addPass({ childPassRan = true })
                        view<DispatcherView>()
                    }
                    entryPoint<io.kmpkt.di.ContextScope<ScopedDomain, io.kmpkt.di.ContextHandle<ScopedDomain>>>("stores")
                }

            val compiled = ContainerCompiler(configurations = listOf(config)).compile(fixture.container)

            fixture.diagnostics.errors.shouldBeEmpty()
            childPassRan shouldBe true
            val ctx = compiled?.contexts?.single()
            ctx?.compiled?.entryPoints?.map { it.name } shouldContainExactly listOf("context", "dispatcher")
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

private object TestStoreContext : io.kmpkt.di.KmpKtContext

private class RootSharedService

private class ScopedInput

private class ScopedDomain(
    @Suppress("unused") val input: ScopedInput,
)

private class ScopedContextFactory : io.kmpkt.di.ContextFactory<ScopedDomain, ScopedInput> {
    override fun create(input: ScopedInput): ScopedDomain = ScopedDomain(input)
}

private class ScopedFeatureService(
    @Suppress("unused") val domain: ScopedDomain,
    @Suppress("unused") val root: RootSharedService,
)

private interface ScopedHandle : io.kmpkt.di.ContextHandle<ScopedDomain>

private interface ScopedView {
    val feature: ScopedFeatureService
}

private interface RootOnlyView {
    val root: RootSharedService
}

private interface DispatcherView {
    val dispatcher: EventDispatcher
}
