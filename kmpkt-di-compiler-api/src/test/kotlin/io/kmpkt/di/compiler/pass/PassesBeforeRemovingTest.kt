package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.Instantiation
import io.kmpkt.di.compiler.OnMissing
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.fixture.DataSource
import io.kmpkt.di.compiler.fixture.DomainEventHandler
import io.kmpkt.di.compiler.fixture.EventDispatcher
import io.kmpkt.di.compiler.fixture.EventHandlerTag
import io.kmpkt.di.compiler.fixture.Fixture
import io.kmpkt.di.compiler.fixture.InMemoryMessageBus
import io.kmpkt.di.compiler.fixture.JdbcOutboxMessageBus
import io.kmpkt.di.compiler.fixture.LoggingMessageBus
import io.kmpkt.di.compiler.fixture.MessageBus
import io.kmpkt.di.compiler.fixture.OtherTag
import io.kmpkt.di.compiler.fixture.RetryConfig
import io.kmpkt.di.compiler.fixture.RetryingMessageBus
import io.kmpkt.di.compiler.fixture.TransportType
import io.kmpkt.di.compiler.fixture.WhenInvoicePaidNotifyWarehouse
import io.kmpkt.di.compiler.fixture.WhenInvoicePaidReleaseShipment
import io.kmpkt.di.compiler.fixture.parameter
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.service
import io.kmpkt.di.compiler.tagged
import io.kmpkt.di.compiler.typeRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe

private fun byId(id: ServiceId): Argument = Argument.Service(ServiceReference.ById(id))

private inline fun <reified T : Any> byKey(qualifier: String? = null): Argument =
    Argument.Service(ServiceReference.ByKey(ServiceKey(typeRef<T>(), qualifier)))

class ApplyAutoconfigurationTest :
    FunSpec({
        test("a service gets the tags registered for a type it is assignable to, and only those") {
            // Arrange
            val fixture = Fixture()
            fixture.container.autoconfigure<DomainEventHandler<*>> { tag(EventHandlerTag()) }
            fixture.container.autoconfigure<MessageBus> { tag(OtherTag("bus")) }
            fixture.container.service<WhenInvoicePaidReleaseShipment>()
            fixture.container.service<InMemoryMessageBus>()

            // Act
            ApplyAutoconfiguration().process(fixture.container)

            // Assert
            fixture.container.find<WhenInvoicePaidReleaseShipment>()?.tags shouldContainExactly listOf(EventHandlerTag())
            fixture.container.find<InMemoryMessageBus>()?.tags shouldContainExactly listOf(OtherTag("bus"))
        }

        test("a tag the service already carries is not added a second time") {
            // Arrange
            val fixture = Fixture()
            fixture.container.autoconfigure<DomainEventHandler<*>> { tag(EventHandlerTag()) }
            fixture.container.autoconfigure<DomainEventHandler<*>> { tag(EventHandlerTag()) }
            fixture.container.service<WhenInvoicePaidReleaseShipment>().tag(EventHandlerTag())
            fixture.container.service<WhenInvoicePaidNotifyWarehouse>()

            // Act
            ApplyAutoconfiguration().process(fixture.container)

            // Assert
            fixture.container.find<WhenInvoicePaidReleaseShipment>()?.tags shouldContainExactly listOf(EventHandlerTag())
            fixture.container.find<WhenInvoicePaidNotifyWarehouse>()?.tags shouldContainExactly listOf(EventHandlerTag())
        }

        test("a service that opted out and a decorator are left alone") {
            // Arrange
            val fixture = Fixture()
            fixture.container.autoconfigure<MessageBus> { tag(OtherTag()) }
            fixture.container.service<InMemoryMessageBus>().autoconfigured(false)
            fixture.container.service<LoggingMessageBus>().decorates<MessageBus>()

            // Act
            ApplyAutoconfiguration().process(fixture.container)

            // Assert
            fixture.container
                .find<InMemoryMessageBus>()
                ?.tags
                .orEmpty()
                .shouldBeEmpty()
            fixture.container
                .find<LoggingMessageBus>()
                ?.tags
                .orEmpty()
                .shouldBeEmpty()
        }
    })

class BindTypedArgumentsTest :
    FunSpec({
        test("an argument passed by type lands on the one parameter that takes it") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<EventDispatcher>(
                parameter<String>("name"),
                parameter<Int>("size"),
                parameter<Boolean>("strict"),
                parameter<TransportType>("transport"),
                parameter<MessageBus?>("bus"),
                parameter<List<DomainEventHandler<*>>>("handlers"),
                parameter<Long>("untouched"),
            )
            fixture.container.service<EventDispatcher> {
                argument("dispatcher")
                argument(3)
                argument(true)
                argument(TransportType.JDBC_OUTBOX)
                argument(service<MessageBus>("command"))
                argument<List<DomainEventHandler<*>>>(tagged<EventHandlerTag>())
            }

            // Act
            BindTypedArguments().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            fixture.container
                .find<EventDispatcher>()
                ?.typedArguments
                .orEmpty()
                .shouldBeEmpty()
            fixture.container.find<EventDispatcher>()?.arguments shouldBe
                mapOf(
                    "name" to literal("dispatcher"),
                    "size" to literal(3),
                    "strict" to literal(true),
                    "transport" to literal(TransportType.JDBC_OUTBOX),
                    "bus" to service<MessageBus>("command"),
                    "handlers" to tagged<EventHandlerTag>(),
                )
        }

        test("a service passed by type fits a parameter of a supertype") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<LoggingMessageBus> {
                argument(service<InMemoryMessageBus>())
            }

            // Act
            BindTypedArguments().process(fixture.container)

            // Assert
            fixture.container.find<LoggingMessageBus>()?.arguments shouldBe mapOf("inner" to service<InMemoryMessageBus>())
        }

        test("a type that fits two parameters, or none that is still free, has to be named") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<EventDispatcher>(parameter<String>("first"), parameter<String>("second"))
            fixture.container.service<EventDispatcher> {
                argument("ambiguous")
            }
            fixture.container.service<JdbcOutboxMessageBus> {
                argument("dataSource", service<DataSource>())
                argument(service<DataSource>())
                argument(7)
            }

            // Act
            BindTypedArguments().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "\"io.kmpkt.di.compiler.fixture.EventDispatcher\" was given an argument of type kotlin.String, which fits " +
                        "2 parameters (first, second). Name the one that is meant.",
                    "\"io.kmpkt.di.compiler.fixture.JdbcOutboxMessageBus\" was given an argument of type " +
                        "io.kmpkt.di.compiler.fixture.DataSource, but has no parameter left that takes one.",
                    "\"io.kmpkt.di.compiler.fixture.JdbcOutboxMessageBus\" was given an argument of type kotlin.Int, " +
                        "but has no parameter left that takes one.",
                )
        }

        test("two arguments of one type cannot both go to the single parameter of that type") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<EventDispatcher>(parameter<String>("name"))
            fixture.container.service<EventDispatcher> {
                argument("one")
                argument("two")
            }

            // Act
            BindTypedArguments().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.single() shouldBe
                "\"io.kmpkt.di.compiler.fixture.EventDispatcher\" was given an argument of type kotlin.String, " +
                "but has no parameter left that takes one."
            fixture.container.find<EventDispatcher>()?.arguments shouldBe mapOf("name" to literal("one"))
        }

        test("a service that cannot be constructed keeps its typed arguments for autowiring to report") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare(typeRef<EventDispatcher>(), parameters = null)
            fixture.container.service<EventDispatcher> {
                argument("x")
            }

            // Act
            BindTypedArguments().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            fixture.container
                .find<EventDispatcher>()
                ?.typedArguments
                ?.size shouldBe 1
        }

        test("a reference by id has no type to go by") {
            val fixture = Fixture()

            shouldThrow<IllegalArgumentException> {
                fixture.container.service<LoggingMessageBus> {
                    argument(Argument.Service(ServiceReference.ById(ServiceId("a"))))
                }
            }
        }
    })

class ResolveDecoratorsTest :
    FunSpec({
        test("a decorator takes the key over and receives the decorated service in its parameter of that type") {
            // Arrange
            val fixture = Fixture()
            val inner =
                fixture.container
                    .service<InMemoryMessageBus>()
                    .bind<MessageBus>()
                    .id
            val decorator =
                fixture.container
                    .service<LoggingMessageBus>()
                    .decorates<MessageBus>()
                    .id

            // Act
            ResolveDecorators().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            fixture.container.find<MessageBus>()?.id shouldBe decorator
            fixture.container.find<InMemoryMessageBus>()?.id shouldBe inner
            fixture.container.definitions
                .getValue(decorator)
                .arguments shouldBe mapOf("inner" to byId(inner))
            fixture.container.definitions
                .getValue(decorator)
                .decorates shouldBe null
        }

        test("the highest priority wraps first and ends up innermost; equal priorities wrap in registration order") {
            // Arrange
            val fixture = Fixture()
            val original =
                fixture.container
                    .service<InMemoryMessageBus>()
                    .bind<MessageBus>()
                    .id
            val first =
                fixture.container
                    .service<LoggingMessageBus>(ServiceId("first"))
                    .decorates<MessageBus>()
                    .id
            val second =
                fixture.container
                    .service<LoggingMessageBus>(ServiceId("second"))
                    .decorates<MessageBus>()
                    .id
            val retry =
                fixture.container
                    .service<RetryingMessageBus>()
                    .decorates<MessageBus>(priority = 10)
                    .id

            // Act
            ResolveDecorators().process(fixture.container)

            // Assert
            fixture.container.definitions
                .getValue(retry)
                .arguments shouldBe mapOf("inner" to byId(original))
            fixture.container.definitions
                .getValue(first)
                .arguments shouldBe mapOf("inner" to byId(retry))
            fixture.container.definitions
                .getValue(second)
                .arguments shouldBe mapOf("inner" to byId(first))
            fixture.container.find<MessageBus>()?.id shouldBe second
        }

        test("the decorated service hands its tags to the decorator, ahead of the decorator's own") {
            // Arrange
            val fixture = Fixture()
            val inner =
                fixture.container
                    .service<InMemoryMessageBus>()
                    .bind<MessageBus>()
                    .tag(OtherTag("inner"))
                    .id
            val decorator =
                fixture.container
                    .service<LoggingMessageBus>()
                    .decorates<MessageBus>()
                    .tag(OtherTag("own"))
                    .id

            // Act
            ResolveDecorators().process(fixture.container)

            // Assert
            fixture.container.definitions
                .getValue(inner)
                .tags
                .shouldBeEmpty()
            fixture.container.definitions
                .getValue(decorator)
                .tags shouldContainExactly listOf(OtherTag("inner"), OtherTag("own"))
        }

        test("an explicit Inner argument says which parameter receives the decorated service, wherever it is nested") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<EventDispatcher>(parameter<List<MessageBus>>("handlers"))
            val inner =
                fixture.container
                    .service<InMemoryMessageBus>()
                    .bind<MessageBus>()
                    .id
            val decorator =
                fixture.container
                    .service<EventDispatcher>()
                    .decorates<MessageBus>()
                    .argument("handlers", Argument.Sequence(listOf(Argument.Inner)))
                    .id

            // Act
            ResolveDecorators().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            fixture.container.definitions
                .getValue(decorator)
                .arguments shouldBe
                mapOf("handlers" to Argument.Sequence(listOf(byId(inner))))
        }

        test("a parameter that was given an argument is not a candidate for the inner service") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<InMemoryMessageBus>().bind<MessageBus>()
            fixture.container
                .service<LoggingMessageBus>()
                .decorates<MessageBus>()
                .argument("inner", service<InMemoryMessageBus>())

            // Act
            ResolveDecorators().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "\"io.kmpkt.di.compiler.fixture.LoggingMessageBus\" decorates io.kmpkt.di.compiler.fixture.MessageBus " +
                        "but has no parameter of that type to receive it.",
                )
        }

        test("two parameters of the decorated type have to be told apart") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<LoggingMessageBus>(parameter<MessageBus>("inner"), parameter<MessageBus?>("fallback"))
            fixture.container.service<InMemoryMessageBus>().bind<MessageBus>()
            fixture.container.service<LoggingMessageBus>().decorates<MessageBus>()

            // Act
            ResolveDecorators().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "\"io.kmpkt.di.compiler.fixture.LoggingMessageBus\" decorates io.kmpkt.di.compiler.fixture.MessageBus " +
                        "and has 2 parameters of that type (inner, fallback). Say which one receives it: " +
                        "argument(name, Argument.Inner).",
                )
            fixture.container.find<MessageBus>()?.id shouldBe ServiceId.of<InMemoryMessageBus>()
        }

        test("decorating a key nothing is bound to is an error") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<LoggingMessageBus>().decorates<MessageBus>()

            // Act
            ResolveDecorators().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "\"io.kmpkt.di.compiler.fixture.LoggingMessageBus\" decorates io.kmpkt.di.compiler.fixture.MessageBus, " +
                        "but no service is bound to that.",
                )
        }

        test("a decorator bound to the key it decorates would wrap itself") {
            // Arrange
            val fixture = Fixture()
            fixture.container
                .service<LoggingMessageBus>()
                .bind<MessageBus>()
                .decorates<MessageBus>()

            // Act
            ResolveDecorators().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.single() shouldBe
                "\"io.kmpkt.di.compiler.fixture.LoggingMessageBus\" decorates io.kmpkt.di.compiler.fixture.MessageBus " +
                "and is bound to it as well, so it would wrap itself. Drop the bind: decorating takes the key over."
        }

        test("an Inner argument on a service that decorates nothing is an error") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<LoggingMessageBus>().argument("inner", Argument.Inner)

            // Act
            ResolveDecorators().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf("\"io.kmpkt.di.compiler.fixture.LoggingMessageBus\" takes an inner service but decorates nothing.")
        }

        test("a decorator that cannot be constructed is left for autowiring to report") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare(typeRef<LoggingMessageBus>(), parameters = null)
            fixture.container.service<InMemoryMessageBus>().bind<MessageBus>()
            fixture.container.service<LoggingMessageBus>().decorates<MessageBus>()

            // Act
            ResolveDecorators().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            fixture.container
                .find<LoggingMessageBus>()
                ?.arguments
                .orEmpty()
                .shouldBeEmpty()
        }
    })

class AutowireTest :
    FunSpec({
        test("a parameter is given the service bound to its type") {
            // Arrange
            val fixture = Fixture()
            fixture.container.external<DataSource>()
            fixture.container.service<JdbcOutboxMessageBus>()

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            fixture.container.find<JdbcOutboxMessageBus>()?.arguments shouldBe mapOf("dataSource" to byKey<DataSource>())
        }

        test("a binding qualified with the parameter's name is preferred to the plain one") {
            // Arrange
            val fixture = Fixture()
            fixture.container.external<DataSource>()
            fixture.container.external<DataSource>(name = "replica", id = ServiceId("replica")).named("dataSource")
            fixture.container.service<JdbcOutboxMessageBus>()

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.container.find<JdbcOutboxMessageBus>()?.arguments shouldBe mapOf("dataSource" to byKey<DataSource>("dataSource"))
        }

        test("an argument that was given is kept, and the rest is autowired") {
            // Arrange
            val fixture = Fixture()
            fixture.container.external<RetryConfig>()
            fixture.container.service<InMemoryMessageBus>()
            fixture.container.service<RetryingMessageBus>().argument("inner", service<InMemoryMessageBus>())

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.container.find<RetryingMessageBus>()?.arguments shouldBe
                mapOf("inner" to byKey<InMemoryMessageBus>(), "config" to byKey<RetryConfig>())
        }

        test("Lazy<T> and () -> T are resolved as T and deferred") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<EventDispatcher>(parameter<Lazy<MessageBus>>("lazy"), parameter<() -> MessageBus>("provider"))
            fixture.container.service<InMemoryMessageBus>().bind<MessageBus>()
            fixture.container.service<EventDispatcher>()

            // Act
            Autowire().process(fixture.container)

            // Assert
            val bus = ServiceReference.ByKey(ServiceKey(typeRef<MessageBus>()))
            fixture.container.find<EventDispatcher>()?.arguments shouldBe
                mapOf(
                    "lazy" to Argument.Deferred(bus, Argument.Deferred.Kind.Lazy),
                    "provider" to Argument.Deferred(bus, Argument.Deferred.Kind.Provider),
                )
        }

        test("what cannot be resolved falls back to the default, then to null") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<EventDispatcher>(
                parameter<MessageBus?>("both", hasDefault = true),
                parameter<MessageBus>("defaulted", hasDefault = true),
                parameter<MessageBus?>("nullable"),
                parameter<Lazy<*>>("star", hasDefault = true),
            )
            fixture.container.service<EventDispatcher>()

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            fixture.container.find<EventDispatcher>()?.arguments shouldBe
                mapOf(
                    "both" to Argument.Default,
                    "defaulted" to Argument.Default,
                    "nullable" to Argument.Literal(ConstValue.Null),
                    "star" to Argument.Default,
                )
        }

        test("a nullable parameter still gets the service when there is one") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<EventDispatcher>(parameter<MessageBus?>("bus", hasDefault = true))
            fixture.container.service<InMemoryMessageBus>().bind<MessageBus>()
            fixture.container.service<EventDispatcher>()

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.container.find<EventDispatcher>()?.arguments shouldBe mapOf("bus" to byKey<MessageBus>())
        }

        test("an unresolvable parameter is reported with the services that are of the type but not bound to it") {
            // Arrange
            val fixture = Fixture()
            fixture.container.external<RetryConfig>()
            fixture.container.service<InMemoryMessageBus>()
            fixture.container.service<RetryingMessageBus>()

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "Cannot autowire parameter \"inner\" of \"io.kmpkt.di.compiler.fixture.RetryingMessageBus\": no service is " +
                        "bound to io.kmpkt.di.compiler.fixture.MessageBus. Of that type, but not bound to it: " +
                        "\"io.kmpkt.di.compiler.fixture.InMemoryMessageBus\". Bind one, or pass the argument.",
                )
            fixture.container.find<RetryingMessageBus>()?.arguments shouldBe mapOf("config" to byKey<RetryConfig>())
        }

        test("an unresolvable parameter with nothing of its type says just that") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<JdbcOutboxMessageBus>()

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "Cannot autowire parameter \"dataSource\" of \"io.kmpkt.di.compiler.fixture.JdbcOutboxMessageBus\": " +
                        "no service is bound to io.kmpkt.di.compiler.fixture.DataSource.",
                )
        }

        test("with autowiring off, a parameter needs an argument or a default") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<RetryingMessageBus>(parameter<MessageBus>("inner"), parameter<RetryConfig>("config", hasDefault = true))
            fixture.container.service<InMemoryMessageBus>().bind<MessageBus>()
            fixture.container.service<RetryingMessageBus>().autowired(false)

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "No argument for parameter \"inner\" of \"io.kmpkt.di.compiler.fixture.RetryingMessageBus\", " +
                        "and autowiring is off for it.",
                )
            fixture.container.find<RetryingMessageBus>()?.arguments shouldBe mapOf("config" to Argument.Default)
        }

        test("an argument for a parameter that does not exist is reported with the ones that do") {
            // Arrange
            val fixture = Fixture()
            fixture.container.external<DataSource>()
            fixture.container.service<JdbcOutboxMessageBus>().argument("datasource", service<DataSource>())
            fixture.container.service<InMemoryMessageBus>().argument("x", service<DataSource>())

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "\"io.kmpkt.di.compiler.fixture.JdbcOutboxMessageBus\" was given an argument \"datasource\", " +
                        "but has no such parameter. It takes: dataSource.",
                    "\"io.kmpkt.di.compiler.fixture.InMemoryMessageBus\" was given an argument \"x\", " +
                        "but has no such parameter. It takes: nothing.",
                )
        }

        test("a service the generated container could not build is reported with the way out, per kind of instantiation") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare(typeRef<InMemoryMessageBus>(), parameters = null)
            fixture.container.service<InMemoryMessageBus>()
            fixture.container.factory<MessageBus>("com.acme", "missing", ServiceId("function"))
            fixture.container.factory(
                typeRef<MessageBus>(),
                Instantiation.ObjectFunction(typeRef<DataSource>(), "missing"),
                ServiceId("object"),
            )
            fixture.container.factory(
                typeRef<MessageBus>(),
                Instantiation.StaticFactory(typeRef<DataSource>(), "missing"),
                ServiceId("static"),
            )
            fixture.container.external<RetryConfig>()

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "Cannot build \"io.kmpkt.di.compiler.fixture.InMemoryMessageBus\": io.kmpkt.di.compiler.fixture.InMemoryMessageBus " +
                        "has no public constructor the generated container could call. Make the class and its primary " +
                        "constructor public, or register a public factory function instead.",
                    "Cannot build \"function\": there is no single public function com.acme.missing returning " +
                        "io.kmpkt.di.compiler.fixture.MessageBus.",
                    "Cannot build \"object\": there is no single public function missing on the object " +
                        "io.kmpkt.di.compiler.fixture.DataSource.",
                    "Cannot build \"static\": there is no single public static method missing on " +
                        "io.kmpkt.di.compiler.fixture.DataSource returning io.kmpkt.di.compiler.fixture.MessageBus.",
                )
        }

        test("a factory function's parameters are autowired like a constructor's") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declareFunction("com.acme.create", parameter<DataSource>("dataSource"))
            fixture.container.external<DataSource>()
            fixture.container.factory<MessageBus>("com.acme", "create")

            // Act
            Autowire().process(fixture.container)

            // Assert
            fixture.container.find<MessageBus>()?.arguments shouldBe mapOf("dataSource" to byKey<DataSource>())
        }
    })

class ResolveTaggedArgumentsTest :
    FunSpec({
        test("a tagged argument becomes the list of tagged services in priority order, nested ones included") {
            // Arrange
            val fixture = Fixture()
            val release =
                fixture.container
                    .service<WhenInvoicePaidReleaseShipment>()
                    .tag(EventHandlerTag())
                    .id
            val notify =
                fixture.container
                    .service<WhenInvoicePaidNotifyWarehouse>()
                    .tag(EventHandlerTag(10))
                    .id
            fixture.container.service<InMemoryMessageBus>()
            fixture.container
                .service<EventDispatcher>()
                .argument("handlers", tagged<EventHandlerTag>())
                .argument("nested", Argument.Dictionary(listOf(Argument.Default to tagged<EventHandlerTag>())))
                .argument("other", Argument.Default)

            // Act
            ResolveTaggedArguments().process(fixture.container)

            // Assert
            val items = listOf(byId(notify), byId(release))
            fixture.container.find<EventDispatcher>()?.arguments shouldBe
                mapOf(
                    "handlers" to Argument.Sequence(items, elementType = typeRef<DomainEventHandler<*>>()),
                    "nested" to Argument.Dictionary(listOf(Argument.Default to Argument.Sequence(items))),
                    "other" to Argument.Default,
                )
        }

        test("a service is left out of its own tagged list unless it asks otherwise") {
            // Arrange
            val fixture = Fixture()
            fixture.types.declare<EventDispatcher>(
                parameter<List<DomainEventHandler<*>>>("handlers"),
                parameter<List<DomainEventHandler<*>>>("all"),
            )
            val other =
                fixture.container
                    .service<WhenInvoicePaidReleaseShipment>()
                    .tag(EventHandlerTag())
                    .id
            val self =
                fixture.container
                    .service<EventDispatcher>()
                    .tag(EventHandlerTag())
                    .argument("handlers", tagged<EventHandlerTag>())
                    .argument("all", tagged<EventHandlerTag>(excludeSelf = false))
                    .id

            // Act
            ResolveTaggedArguments().process(fixture.container)

            // Assert
            val elementType = typeRef<DomainEventHandler<*>>()
            fixture.container.find<EventDispatcher>()?.arguments shouldBe
                mapOf(
                    "handlers" to Argument.Sequence(listOf(byId(other)), elementType = elementType),
                    "all" to Argument.Sequence(listOf(byId(other), byId(self)), elementType = elementType),
                )
        }

        test("a tag nobody carries resolves to an empty list") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<EventDispatcher>().argument("handlers", tagged<EventHandlerTag>())

            // Act
            ResolveTaggedArguments().process(fixture.container)

            // Assert
            fixture.container.find<EventDispatcher>()?.arguments shouldBe
                mapOf("handlers" to Argument.Sequence(emptyList(), elementType = typeRef<DomainEventHandler<*>>()))
        }
    })

class ResolveAliasesTest :
    FunSpec({
        test("references by key become references by id, deferred ones staying deferred") {
            // Arrange
            val fixture = Fixture()
            val bus =
                fixture.container
                    .service<InMemoryMessageBus>()
                    .bind<MessageBus>()
                    .id
            fixture.container
                .service<EventDispatcher>()
                .argument("plain", service<MessageBus>())
                .argument(
                    "deferred",
                    Argument.Deferred(ServiceReference.ByKey(ServiceKey(typeRef<MessageBus>())), Argument.Deferred.Kind.Lazy),
                ).argument("nested", Argument.Sequence(listOf(service<MessageBus>(), Argument.Default)))
                .argument("resolved", byId(bus))

            // Act
            ResolveAliases().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
            fixture.container.find<EventDispatcher>()?.arguments shouldBe
                mapOf(
                    "plain" to byId(bus),
                    "deferred" to Argument.Deferred(ServiceReference.ById(bus), Argument.Deferred.Kind.Lazy),
                    "nested" to Argument.Sequence(listOf(byId(bus), Argument.Default)),
                    "resolved" to byId(bus),
                )
        }

        test("a missing service is null where the reference allows it, and an error where it does not") {
            // Arrange
            val fixture = Fixture()
            val optional = ServiceReference.ByKey(ServiceKey(typeRef<MessageBus>()), OnMissing.Null)
            fixture.container
                .service<EventDispatcher>()
                .argument("optional", Argument.Service(optional))
                .argument("required", service<MessageBus>("command"))

            // Act
            ResolveAliases().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "\"io.kmpkt.di.compiler.fixture.EventDispatcher\" references io.kmpkt.di.compiler.fixture.MessageBus " +
                        "named \"command\", but no service is bound to that.",
                )
            fixture.container.find<EventDispatcher>()?.arguments shouldBe
                mapOf("optional" to Argument.Literal(ConstValue.Null), "required" to service<MessageBus>("command"))
        }
    })

class CheckReferencesTest :
    FunSpec({
        test("a resolved graph without cycles passes") {
            // Arrange
            val fixture = Fixture()
            val bus =
                fixture.container
                    .service<InMemoryMessageBus>()
                    .bind<MessageBus>()
                    .id
            fixture.container.service<LoggingMessageBus>(ServiceId("a")).argument("inner", byId(bus))
            fixture.container.service<LoggingMessageBus>(ServiceId("b")).argument("inner", byId(bus))
            fixture.container.entryPoint("bus", ServiceKey(typeRef<MessageBus>()))

            // Act
            CheckReferences().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
        }

        test("an entry point nothing is bound to is an error") {
            // Arrange
            val fixture = Fixture()
            fixture.container.entryPoint("bus", ServiceKey(typeRef<MessageBus>(), "command"))

            // Act
            CheckReferences().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf(
                    "Entry point \"bus\" asks for io.kmpkt.di.compiler.fixture.MessageBus named \"command\", " +
                        "but no service is bound to that.",
                )
        }

        test("a reference to an id that does not exist is an error") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<LoggingMessageBus>().argument("inner", byId(ServiceId("gone")))

            // Act
            CheckReferences().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf("\"io.kmpkt.di.compiler.fixture.LoggingMessageBus\" references \"gone\", which does not exist.")
        }

        test("a cycle is reported once, as the path that closes it") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<LoggingMessageBus>(ServiceId("entry")).argument("inner", byId(ServiceId("a")))
            fixture.container.service<LoggingMessageBus>(ServiceId("a")).argument("inner", byId(ServiceId("b")))
            fixture.container.service<LoggingMessageBus>(ServiceId("b")).argument("inner", byId(ServiceId("c")))
            fixture.container.service<LoggingMessageBus>(ServiceId("c")).argument("inner", byId(ServiceId("a")))

            // Act
            CheckReferences().process(fixture.container)

            // Assert
            fixture.diagnostics.errors shouldContainExactly
                listOf("Circular reference: a -> b -> c -> a. Break it by taking a Lazy<…> or a () -> … in one of them.")
        }

        test("a service that needs itself is a cycle of one") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<LoggingMessageBus>(ServiceId("a")).argument("inner", byId(ServiceId("a")))

            // Act
            CheckReferences().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.single() shouldBe
                "Circular reference: a -> a. Break it by taking a Lazy<…> or a () -> … in one of them."
        }

        test("a cycle that passes through a deferred reference is none") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<LoggingMessageBus>(ServiceId("a")).argument("inner", byId(ServiceId("b")))
            fixture.container
                .service<LoggingMessageBus>(ServiceId("b"))
                .argument("inner", Argument.Deferred(ServiceReference.ById(ServiceId("a")), Argument.Deferred.Kind.Provider))

            // Act
            CheckReferences().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
        }

        test("a diamond is not a cycle") {
            // Arrange
            val fixture = Fixture()
            fixture.container
                .service<LoggingMessageBus>(ServiceId("top"))
                .argument("inner", Argument.Sequence(listOf(byId(ServiceId("left")), byId(ServiceId("right")))))
            fixture.container.service<LoggingMessageBus>(ServiceId("left")).argument("inner", byId(ServiceId("bottom")))
            fixture.container.service<LoggingMessageBus>(ServiceId("right")).argument("inner", byId(ServiceId("bottom")))
            fixture.container.service<InMemoryMessageBus>(ServiceId("bottom"))

            // Act
            CheckReferences().process(fixture.container)

            // Assert
            fixture.diagnostics.errors.shouldBeEmpty()
        }
    })
