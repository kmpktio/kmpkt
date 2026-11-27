package io.kmpkt.di.compiler

import io.kmpkt.di.compiler.fixture.DataSource
import io.kmpkt.di.compiler.fixture.DomainEventHandler
import io.kmpkt.di.compiler.fixture.EventHandlerTag
import io.kmpkt.di.compiler.fixture.Fixture
import io.kmpkt.di.compiler.fixture.InMemoryMessageBus
import io.kmpkt.di.compiler.fixture.JdbcOutboxMessageBus
import io.kmpkt.di.compiler.fixture.MessageBus
import io.kmpkt.di.compiler.fixture.MessageBusFactory
import io.kmpkt.di.compiler.fixture.OtherTag
import io.kmpkt.di.compiler.fixture.RetryConfig
import io.kmpkt.di.compiler.fixture.RetryingMessageBus
import io.kmpkt.di.compiler.fixture.TransportType
import io.kmpkt.di.compiler.fixture.WhenInvoicePaidNotifyWarehouse
import io.kmpkt.di.compiler.fixture.WhenInvoicePaidReleaseShipment
import io.kmpkt.di.compiler.fixture.WhenOrderShippedSendInvoice
import io.kmpkt.di.compiler.fixture.createMessageBus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ContainerBuilderTest :
    FunSpec({
        test("typeRef keeps type arguments, star projections and nullability") {
            typeRef<Map<String, List<DomainEventHandler<*>>?>>().toString() shouldBe
                "kotlin.collections.Map<kotlin.String, kotlin.collections.List<io.kmpkt.di.compiler.fixture.DomainEventHandler<*>>?>"
        }

        test("typeRef refuses a type that has no name") {
            class Local

            shouldThrow<IllegalArgumentException> { typeRef<Local>() }
        }

        test("nonNull drops the question mark and nothing else") {
            typeRef<List<String?>?>().nonNull() shouldBe typeRef<List<String?>>()
            typeRef<String>().nonNull() shouldBe typeRef<String>()
        }

        test("a registered service is named after its type, built by its constructor and found under its own type") {
            // Arrange
            val container = Fixture().container

            // Act
            val id = container.service<InMemoryMessageBus>().id

            // Assert
            id shouldBe ServiceId("io.kmpkt.di.compiler.fixture.InMemoryMessageBus")
            container.find<InMemoryMessageBus>() shouldBe ServiceDefinition(id, typeRef<InMemoryMessageBus>())
            container.find<MessageBus>().shouldBeNull()
        }

        test("bind makes the service what consumers of that type get, replacing an earlier binding") {
            // Arrange
            val container = Fixture().container
            container.service<InMemoryMessageBus>().bind<MessageBus>()

            // Act
            container.service<JdbcOutboxMessageBus>().bind<MessageBus>()

            // Assert
            container.find<MessageBus>()?.id shouldBe ServiceId.of<JdbcOutboxMessageBus>()
        }

        test("bind and named add a qualified key next to the plain one") {
            // Arrange
            val container = Fixture().container

            // Act
            container
                .service<InMemoryMessageBus>(ServiceId("bus.command"))
                .bind<MessageBus>("command")
                .named("command")

            // Assert
            container.find<MessageBus>("command")?.id shouldBe ServiceId("bus.command")
            container.find<InMemoryMessageBus>("command")?.id shouldBe ServiceId("bus.command")
            container.find<MessageBus>().shouldBeNull()
        }

        test("the fluent calls land on the definition") {
            // Arrange
            val container = Fixture().container

            // Act
            container
                .service<RetryingMessageBus>()
                .tag(EventHandlerTag(3))
                .decorates<MessageBus>(priority = 10)
                .argument("config", service<RetryConfig>())
                .prototype()
                .autowired(false)
                .autoconfigured(false)

            // Assert
            container.find<RetryingMessageBus>() shouldBe
                ServiceDefinition(
                    id = ServiceId.of<RetryingMessageBus>(),
                    type = typeRef<RetryingMessageBus>(),
                    arguments = mapOf("config" to Argument.Service(ServiceReference.ByKey(ServiceKey(typeRef<RetryConfig>())))),
                    tags = listOf(EventHandlerTag(3)),
                    scope = Scope.Prototype,
                    decorates = Decoration(ServiceKey(typeRef<MessageBus>()), 10),
                    autowired = false,
                    autoconfigured = false,
                )
        }

        test("a registration block configures the service it registers, for every kind of registration") {
            // Arrange
            val container = Fixture().container

            // Act
            container.service<InMemoryMessageBus> {
                bind<MessageBus>()
                tag(OtherTag("constructed"))
            }
            container.factory(::createMessageBus, ServiceId("made")) {
                tag(OtherTag("made"))
            }
            container.factory<MessageBus>("com.acme", "create", ServiceId("named")) {
                tag(OtherTag("named"))
            }
            container.external<RetryConfig> {
                tag(OtherTag("handed in"))
            }

            // Assert
            container.find<MessageBus>()?.id shouldBe ServiceId("named")
            container.tagged<OtherTag>().map { it.tag.label } shouldContainExactly listOf("constructed", "made", "named", "handed in")
        }

        test("a factory given as a reference to a top-level function is registered as its return type") {
            // Arrange
            val container = Fixture().container

            // Act
            container.factory(::createMessageBus)

            // Assert
            container.find<MessageBus>() shouldBe
                ServiceDefinition(
                    id = ServiceId.of<MessageBus>(),
                    type = typeRef<MessageBus>(),
                    instantiation = Instantiation.Function("io.kmpkt.di.compiler.fixture", "createMessageBus"),
                )
        }

        test("a factory given as a reference to an object's function keeps the object") {
            // Arrange
            val container = Fixture().container

            // Act
            container.factory(MessageBusFactory::create)

            // Assert
            container.find<MessageBus>()?.instantiation shouldBe Instantiation.ObjectFunction(typeRef<MessageBusFactory>(), "create")
        }

        test("a reference to a static method of a Java class is a static factory") {
            // Arrange
            val container = Fixture().container

            // Act
            container.factory(java.time.Clock::systemUTC)

            // Assert
            container.find<java.time.Clock>()?.instantiation shouldBe
                Instantiation.StaticFactory(typeRef<java.time.Clock>(), "systemUTC")
        }

        test("a factory can be named instead of referenced") {
            // Arrange
            val container = Fixture().container

            // Act
            container.factory<MessageBus>("com.acme", "create", ServiceId("bus"))

            // Assert
            container.definitions[ServiceId("bus")]?.instantiation shouldBe Instantiation.Function("com.acme", "create")
        }

        test("a lambda is not a function the generated code could call by name") {
            val container = Fixture().container
            val notAReference =
                object : kotlin.reflect.KFunction<MessageBus> by ::createMessageBus {}

            shouldThrow<IllegalArgumentException> { container.factory(notAReference) }
        }

        test("an external service becomes a constructor parameter named after its type, or as told") {
            // Arrange
            val container = Fixture().container

            // Act
            container.external<RetryConfig>()
            container.external<DataSource>("database")

            // Assert
            container.find<RetryConfig>()?.instantiation shouldBe Instantiation.External("retryConfig")
            container.find<DataSource>()?.instantiation shouldBe Instantiation.External("database")
        }

        test("removing a definition removes every binding to it") {
            // Arrange
            val container = Fixture().container
            val id = container.service<InMemoryMessageBus>().bind<MessageBus>().id
            container.service<JdbcOutboxMessageBus>()

            // Act
            container.remove(id)

            // Assert
            container.has(id) shouldBe false
            container.aliases shouldBe mapOf(ServiceKey(typeRef<JdbcOutboxMessageBus>()) to ServiceId.of<JdbcOutboxMessageBus>())
        }

        test("update changes a definition but never its id") {
            // Arrange
            val container = Fixture().container
            container.service<InMemoryMessageBus>()

            // Act
            container.update<InMemoryMessageBus> { it.copy(id = ServiceId("other"), scope = Scope.Prototype) }

            // Assert
            container.definitions.keys shouldContainExactly setOf(ServiceId.of<InMemoryMessageBus>())
            container.find<InMemoryMessageBus>()?.scope shouldBe Scope.Prototype
            container.find<InMemoryMessageBus>()?.id shouldBe ServiceId.of<InMemoryMessageBus>()
        }

        test("updating a service that does not exist is a programming error") {
            val container = Fixture().container

            shouldThrow<IllegalArgumentException> { container.update(ServiceId("missing")) { it } }
                .message shouldBe "No service \"missing\" to update"
        }

        test("tagged services come highest priority first, in registration order among equals, other tags ignored") {
            // Arrange
            val container = Fixture().container
            container.service<WhenInvoicePaidReleaseShipment>().tag(EventHandlerTag())
            container.service<WhenInvoicePaidNotifyWarehouse>().tag(OtherTag()).tag(EventHandlerTag(10))
            container.service<WhenOrderShippedSendInvoice>().tag(EventHandlerTag())
            container.service<InMemoryMessageBus>().tag(OtherTag("bus"))

            // Act
            val tagged = container.tagged<EventHandlerTag>()

            // Assert
            tagged.map { it.definition.id to it.tag } shouldContainExactly
                listOf(
                    ServiceId.of<WhenInvoicePaidNotifyWarehouse>() to EventHandlerTag(10),
                    ServiceId.of<WhenInvoicePaidReleaseShipment>() to EventHandlerTag(),
                    ServiceId.of<WhenOrderShippedSendInvoice>() to EventHandlerTag(),
                )
            container.tagged<OtherTag>().map { it.tag.label } shouldContainExactly listOf("", "bus")
        }

        test("nothing is tagged in an empty container") {
            Fixture().container.tagged<EventHandlerTag>().shouldBeEmpty()
        }

        test("literal helpers write values the generator can print") {
            literal("a") shouldBe Argument.Literal(ConstValue.Text("a"))
            literal(3) shouldBe Argument.Literal(ConstValue.Number(3))
            literal(true) shouldBe Argument.Literal(ConstValue.Flag(true))
            literal(TransportType.JDBC_OUTBOX) shouldBe
                Argument.Literal(ConstValue.EnumEntry(typeRef<TransportType>(), "JDBC_OUTBOX"))
            classLiteral<MessageBus>() shouldBe Argument.Literal(ConstValue.ClassLiteral(typeRef<MessageBus>()))
            tagged<EventHandlerTag>(excludeSelf = false) shouldBe Argument.Tagged(EventHandlerTag::class, excludeSelf = false)
            service<MessageBus>("command") shouldBe
                Argument.Service(ServiceReference.ByKey(ServiceKey(typeRef<MessageBus>(), "command")))
        }

        test("a service names itself with whoever registered it") {
            val definition = ServiceDefinition(ServiceId("a"), typeRef<String>())

            definition.describe() shouldBe "\"a\""
            definition.copy(source = "com.acme.Bundle").describe() shouldBe "\"a\" (registered by com.acme.Bundle)"
            ServiceKey(typeRef<String>(), "x").toString() shouldBe "kotlin.String named \"x\""
            ServiceKey(typeRef<String>()).toString() shouldBe "kotlin.String"
        }

        test("diagnostics collect instead of throwing") {
            // Arrange
            val diagnostics = CollectingDiagnostics()

            // Act
            diagnostics.warn("careful")
            val before = diagnostics.hasErrors
            diagnostics.error("broken")

            // Assert
            before shouldBe false
            diagnostics.hasErrors shouldBe true
            diagnostics.errors shouldContainExactly listOf("broken")
            diagnostics.warnings shouldContainExactly listOf("careful")
        }

        test("factory accepts companion object factory functions") {
            val fixture = Fixture()

            fixture.container.factory(CompanionProduct::create)

            val definition = fixture.container.definitions.getValue(ServiceId.of<CompanionProduct>())
            definition.instantiation shouldBe Instantiation.ObjectFunction(typeRef<CompanionProduct.Companion>(), "create")
        }

        test("rejects view declarations on the root container outside a context block") {
            val fixture = Fixture()

            fixture.container.view<SampleView>()

            fixture.diagnostics.hasErrors shouldBe true
        }

        test("registers scoped child container with views and merges context extensions") {
            val fixture = Fixture()
            fixture.types.declare<SampleHandle>(
                supertypes = listOf(TypeRef("io.kmpkt.di.ContextHandle", listOf(TypeArgument.Of(typeRef<SampleDomain>())))),
            )

            fixture.container.context<SampleContext, SampleHandle, SampleInput> {
                service<InMemoryMessageBus>()
                view<SampleView>()
            }
            fixture.container.context<SampleContext> {
                service<RetryConfig>()
            }

            fixture.diagnostics.hasErrors shouldBe false
            val ctx = fixture.container.contexts.getValue(typeRef<SampleContext>().qualifiedName)
            ctx.contextType shouldBe typeRef<SampleDomain>()
            ctx.inputType shouldBe typeRef<SampleInput>()
            ctx.handleType shouldBe typeRef<SampleHandle>()
            ctx.builder.views shouldContainExactly listOf(typeRef<SampleView>())
            ctx.builder.definitions.keys
                .toList() shouldContainExactly
                listOf(
                    ServiceId.of<SampleDomain>(),
                    ServiceId.of<InMemoryMessageBus>(),
                    ServiceId.of<RetryConfig>(),
                )
        }

        test("context extension before declaration is filled in when declared later") {
            val fixture = Fixture()
            fixture.types.declare<SampleHandle>(
                supertypes = listOf(TypeRef("io.kmpkt.di.ContextHandle", listOf(TypeArgument.Of(typeRef<SampleDomain>())))),
            )

            fixture.container.context<SampleContext> {
                service<RetryConfig>()
            }
            fixture.container.context<SampleContext, SampleHandle, SampleInput> {
                service<InMemoryMessageBus>()
            }

            val ctx = fixture.container.contexts.getValue(typeRef<SampleContext>().qualifiedName)
            ctx.contextType shouldBe typeRef<SampleDomain>()
            ctx.inputType shouldBe typeRef<SampleInput>()
            ctx.handleType shouldBe typeRef<SampleHandle>()
            ctx.builder.entryPoints.map { it.name } shouldContainExactly listOf("context")
            ctx.builder.definitions.keys
                .toList() shouldContainExactly
                listOf(
                    ServiceId.of<RetryConfig>(),
                    ServiceId.of<SampleDomain>(),
                    ServiceId.of<InMemoryMessageBus>(),
                )
        }

        test("context declaration fails when ContextHandle has no type argument") {
            val fixture = Fixture()
            fixture.types.declare<RawSampleHandle>(
                supertypes = listOf(TypeRef("io.kmpkt.di.ContextHandle")),
            )

            fixture.container.context<SampleContext, RawSampleHandle, SampleInput>()

            fixture.diagnostics.hasErrors shouldBe true
        }

        test("allows idempotent context redeclaration with identical types") {
            val fixture = Fixture()
            fixture.types.declare<SampleHandle>(
                supertypes = listOf(TypeRef("io.kmpkt.di.ContextHandle", listOf(TypeArgument.Of(typeRef<SampleDomain>())))),
            )

            fixture.container.context<SampleContext, SampleHandle, SampleInput>()
            fixture.container.context<SampleContext, SampleHandle, SampleInput>()

            fixture.diagnostics.hasErrors shouldBe false
        }

        test("redeclaring a context with conflicting types reports a diagnostic error") {
            val fixture = Fixture()
            fixture.types.declare<SampleHandle>(
                supertypes = listOf(TypeRef("io.kmpkt.di.ContextHandle", listOf(TypeArgument.Of(typeRef<SampleDomain>())))),
            )

            fixture.container.context<SampleContext, SampleHandle, SampleInput>()
            fixture.container.context<SampleContext, SampleHandle, RetryConfig>()

            fixture.diagnostics.errors.single() shouldBe
                "Context \"${typeRef<SampleContext>().qualifiedName}\" is already declared as " +
                "(${typeRef<SampleDomain>()}, ${typeRef<SampleInput>()}, ${typeRef<SampleHandle>()}) " +
                "and cannot be redeclared as (${typeRef<SampleDomain>()}, ${typeRef<RetryConfig>()}, ${typeRef<SampleHandle>()})."
        }
    })

private interface RawSampleHandle : io.kmpkt.di.ContextHandle<SampleDomain>

private class CompanionProduct private constructor() {
    companion object {
        fun create(): CompanionProduct = CompanionProduct()
    }
}

private object SampleContext : io.kmpkt.di.KmpKtContext

private class SampleDomain

private class SampleInput

private interface SampleHandle : io.kmpkt.di.ContextHandle<SampleDomain>

private interface SampleView
