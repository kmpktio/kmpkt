package io.kmpkt.projection.di

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.di.KmpKtApplication
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.CollectingDiagnostics
import io.kmpkt.di.compiler.CompiledContainer
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ContainerCompiler
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.fixture.FakeTypeOracle
import io.kmpkt.di.compiler.fixture.parameter
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.event.store.EventStore
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.di.BusRef
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.messenger.di.fixture.declareMessenger
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.persistence.Persistence
import io.kmpkt.projection.ProjectEvent
import io.kmpkt.projection.ProjectEventHandler
import io.kmpkt.projection.ProjectionChange
import io.kmpkt.projection.ProjectionExecutor
import io.kmpkt.projection.ProjectionStore
import io.kmpkt.projection.ProjectionTransport
import io.kmpkt.projection.ProjectionView
import io.kmpkt.projection.Projector
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Clock
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private interface TodoAggregate : AggregateRoot<TodoAggregate>

private class TodoAdded : DomainEvent<TodoAggregate> {
    override val aggregate: AggregateId<TodoAggregate> get() = error("unused")
    override val version: Long = 1L
    override val occurredAt: Instant = Instant.EPOCH
}

private data class TodoRow(val id: String)

private data class OtherRow(val id: String)

private class TodoAddedProjector : Projector<TodoAdded, TodoRow> {
    override suspend fun project(
        event: TodoAdded,
        view: ProjectionView<TodoRow>,
    ): List<ProjectionChange<TodoRow>> = emptyList()
}

private class OtherProjector : Projector<TodoAdded, OtherRow> {
    override suspend fun project(
        event: TodoAdded,
        view: ProjectionView<OtherRow>,
    ): List<ProjectionChange<OtherRow>> = emptyList()
}

private class UntypedProjector : Projector<TodoAdded, TodoRow> {
    override suspend fun project(
        event: TodoAdded,
        view: ProjectionView<TodoRow>,
    ): List<ProjectionChange<TodoRow>> = emptyList()
}

private class FakePersistence

private class FakeEventStore

private class FakeTodoStore

private class Compilation(
    components: List<Component<*>> = listOf(ProjectionComponent(), MessengerComponent()),
    declareEntryPoint: Boolean = true,
    configure: ContainerBuilder.() -> Unit = {},
) {
    val types =
        FakeTypeOracle().apply {
            declareMessenger()
            declare<ProjectionExecutor<TodoRow>>(
                parameter<Map<KClass<out DomainEvent<*>>, List<Projector<*, TodoRow>>>>("initial", hasDefault = true),
            )
            declare<ProjectionTransport<TodoRow>>(
                parameter<String>("projection"),
                parameter<Persistence>("persistence"),
                parameter<EventStore>("eventStore"),
                parameter<ProjectionStore<TodoRow>>("store"),
                parameter<ProjectionExecutor<TodoRow>>("projectors"),
                parameter<Clock>("clock", hasDefault = true),
                parameter<Int>("batchSize"),
                parameter<Duration>("retryAfter"),
            )
            declare<ProjectEventHandler<TodoRow>>(
                parameter<String>("projection"),
                parameter<ProjectionStore<TodoRow>>("store"),
                parameter<ProjectionExecutor<TodoRow>>("projectors"),
                supertypes = listOf(typeRef<MessageHandler<ProjectEvent, Unit>>()),
            )
            declare<FakePersistence>(supertypes = listOf(typeRef<Persistence>()))
            declare<FakeEventStore>(supertypes = listOf(typeRef<EventStore>()))
            declare<FakeTodoStore>(supertypes = listOf(typeRef<ProjectionStore<TodoRow>>()))
            declare<TodoAddedProjector>(supertypes = listOf(typeRef<Projector<TodoAdded, TodoRow>>()))
            declare<OtherProjector>(supertypes = listOf(typeRef<Projector<TodoAdded, OtherRow>>()))
            declare<UntypedProjector>(supertypes = listOf(typeRef<Projector<*, *>>()))
        }
    private val diagnostics = CollectingDiagnostics()

    val errors: List<String> get() = diagnostics.errors
    val compiled: CompiledContainer? =
        ContainerBuilder(types, diagnostics)
            .apply {
                if (declareEntryPoint) {
                    entryPoint("todos", ServiceKey(typeRef<ProjectionExecutor<TodoRow>>()))
                    entryPoint("transport", ServiceKey(typeRef<ProjectionTransport<TodoRow>>()))
                }
            }.let {
                ContainerCompiler(components, listOf(Configuration<KmpKtApplication.Default>(configure))).compile(it)
            }
}

class ProjectionComponentTest :
    FunSpec({
        test("does not register projection services when no projections are configured") {
            val result = Compilation(declareEntryPoint = false)

            result.errors.shouldBeEmpty()
            result.compiled.shouldNotBeNull().definitions.none {
                it.id.value.startsWith("projection.")
            } shouldBe true
        }

        test("wires matching projectors for the projection row type and forwards transport options") {
            val result =
                Compilation {
                    service<FakePersistence> { bind<Persistence>() }
                    service<FakeEventStore> { bind<EventStore>() }
                    service<FakeTodoStore> { bind<ProjectionStore<TodoRow>>() }
                    service<TodoAddedProjector>()
                    service<OtherProjector>()
                    var commands: BusRef? = null
                    messenger {
                        commands = bus("command")
                    }
                    projection {
                        commandBus = commands
                        projection<TodoRow>(name = "todos", batchSize = 25, retryAfter = 30.seconds)
                    }
                    entryPoint("commandBus", ServiceKey(typeRef<MessageBus>(), "command"))
                }

            result.errors.shouldBeEmpty()
            val compiled = result.compiled.shouldNotBeNull()

            val transport = compiled.definition(ServiceId("projection.transport.todos"))
            transport.arguments["projection"] shouldBe literal("todos")
            transport.arguments["batchSize"] shouldBe literal(25)
            transport.arguments["retryAfter"] shouldBe literal(30.seconds)

            val executor = compiled.definition(ServiceId("projection.executor.todos"))
            val initial = executor.arguments["initial"] as Argument.Dictionary
            initial.entries shouldContainExactly
                listOf(
                    Argument.Literal(ConstValue.ClassLiteral(typeRef<TodoAdded>())) to
                        Argument.Sequence(
                            listOf(Argument.Service(ServiceReference.ById(ServiceId.of<TodoAddedProjector>()))),
                        ),
                )

            val bus = compiled.definition(ServiceId("messenger.bus.command.handlers"))
            val handlers = bus.arguments["handlers"] as Argument.Dictionary
            handlers.entries.map { it.first } shouldBe
                listOf(Argument.Literal(ConstValue.ClassLiteral(typeRef<ProjectEvent>())))
        }

        test("reports a diagnostic when a Projector omits its event or row type arguments") {
            val result =
                Compilation {
                    service<FakePersistence> { bind<Persistence>() }
                    service<FakeEventStore> { bind<EventStore>() }
                    service<FakeTodoStore> { bind<ProjectionStore<TodoRow>>() }
                    service<UntypedProjector>()
                    messenger {
                        bus("command")
                    }
                    projection {
                        projection<TodoRow>(name = "todos")
                    }
                }

            result.errors.single() shouldContain "Cannot tell event and row types of"
        }
    })
