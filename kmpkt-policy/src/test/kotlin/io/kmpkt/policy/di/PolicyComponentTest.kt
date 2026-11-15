package io.kmpkt.policy.di

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.command.Command
import io.kmpkt.command.CommandBus
import io.kmpkt.command.di.CommandComponent
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
import io.kmpkt.policy.ExecutePolicyCommandsForEvent
import io.kmpkt.policy.ExecutePolicyCommandsForEventHandler
import io.kmpkt.policy.Policy
import io.kmpkt.policy.PolicyExecutor
import io.kmpkt.policy.PolicyTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private interface TodoAggregate : AggregateRoot<TodoAggregate>

private class TodoAdded : DomainEvent<TodoAggregate> {
    override val aggregate: AggregateId<TodoAggregate> get() = error("unused")
    override val version: Long = 1L
    override val occurredAt: Instant = Instant.EPOCH
}

private class NotifyOnTodoAdded : Policy<TodoAdded> {
    override fun decide(event: TodoAdded): List<Command> = emptyList()
}

private class UntypedPolicy : Policy<TodoAdded> {
    override fun decide(event: TodoAdded): List<Command> = emptyList()
}

private class FakePersistence

private class FakeEventStore

private class Compilation(
    components: List<Component<*>> = listOf(PolicyComponent(), CommandComponent(), MessengerComponent()),
    configure: ContainerBuilder.() -> Unit = {},
) {
    val types =
        FakeTypeOracle().apply {
            declareMessenger()
            declare<CommandBus>(parameter<MessageBus>("bus"))
            declare<PolicyExecutor>(
                parameter<Map<KClass<out DomainEvent<*>>, List<Policy<*>>>>("initial", hasDefault = true),
            )
            declare<PolicyTransport>(
                parameter<Persistence>("persistence"),
                parameter<EventStore>("eventStore"),
                parameter<String>("name"),
                parameter<Duration>("collectGarbageEvery"),
            )
            declare<ExecutePolicyCommandsForEventHandler>(
                parameter<PolicyExecutor>("policies"),
                parameter<Lazy<CommandBus>>("commandBus"),
                supertypes = listOf(typeRef<MessageHandler<ExecutePolicyCommandsForEvent, Unit>>()),
            )
            declare<FakePersistence>(supertypes = listOf(typeRef<Persistence>()))
            declare<FakeEventStore>(supertypes = listOf(typeRef<EventStore>()))
            declare<NotifyOnTodoAdded>(supertypes = listOf(typeRef<Policy<TodoAdded>>()))
            declare<UntypedPolicy>(supertypes = listOf(typeRef<Policy<*>>()))
        }
    private val diagnostics = CollectingDiagnostics()

    val errors: List<String> get() = diagnostics.errors
    val compiled: CompiledContainer? =
        ContainerBuilder(types, diagnostics)
            .apply {
                entryPoint("executor", ServiceKey(typeRef<PolicyExecutor>()))
                entryPoint("transport", ServiceKey(typeRef<PolicyTransport>()))
            }.let {
                ContainerCompiler(components, listOf(Configuration<KmpKtApplication.Default>(configure))).compile(it)
            }
}

class PolicyComponentTest :
    FunSpec({
        test("registers PolicyExecutor, PolicyTransport, and policy command handler with defaults") {
            val result =
                Compilation {
                    service<FakePersistence> { bind<Persistence>() }
                    service<FakeEventStore> { bind<EventStore>() }
                    service<NotifyOnTodoAdded>()
                }

            result.errors.shouldBeEmpty()
            val compiled = result.compiled.shouldNotBeNull()

            val transport = compiled.definition(ServiceId.of<PolicyTransport>())
            transport.arguments["name"] shouldBe literal("policies")
            transport.arguments["collectGarbageEvery"] shouldBe literal(1.hours)

            val executor = compiled.definition(ServiceId.of<PolicyExecutor>())
            val initial = executor.arguments["initial"] as Argument.Dictionary
            initial.entries shouldContainExactly
                listOf(
                    Argument.Literal(ConstValue.ClassLiteral(typeRef<TodoAdded>())) to
                        Argument.Sequence(
                            listOf(Argument.Service(ServiceReference.ById(ServiceId.of<NotifyOnTodoAdded>()))),
                        ),
                )
        }

        test("forwards configured name, collectGarbageEvery, and commandBus") {
            val result =
                Compilation {
                    service<FakePersistence> { bind<Persistence>() }
                    service<FakeEventStore> { bind<EventStore>() }
                    var customCommands: BusRef? = null
                    messenger {
                        customCommands = bus("custom-commands")
                    }
                    policy {
                        name = "custom-policies"
                        collectGarbageEvery = 15.minutes
                        commandBus = customCommands
                    }
                    entryPoint("customBus", ServiceKey(typeRef<MessageBus>(), "custom-commands"))
                }

            result.errors.shouldBeEmpty()
            val compiled = result.compiled.shouldNotBeNull()

            val transport = compiled.definition(ServiceId.of<PolicyTransport>())
            transport.arguments["name"] shouldBe literal("custom-policies")
            transport.arguments["collectGarbageEvery"] shouldBe literal(15.minutes)

            val bus = compiled.definition(ServiceId("messenger.bus.custom-commands.handlers"))
            val handlers = bus.arguments["handlers"] as Argument.Dictionary
            val policyHandlerKey = Argument.Literal(ConstValue.ClassLiteral(typeRef<ExecutePolicyCommandsForEvent>()))
            handlers.entries.map { it.first } shouldBe listOf(policyHandlerKey)
        }

        test("reports a diagnostic when a Policy omits its event type argument") {
            val result =
                Compilation {
                    service<FakePersistence> { bind<Persistence>() }
                    service<FakeEventStore> { bind<EventStore>() }
                    service<UntypedPolicy>()
                }

            result.errors.single() shouldContain "Cannot tell which event"
        }
    })
