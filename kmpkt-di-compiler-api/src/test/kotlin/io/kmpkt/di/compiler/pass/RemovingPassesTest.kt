package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.fixture.DataSource
import io.kmpkt.di.compiler.fixture.EventDispatcher
import io.kmpkt.di.compiler.fixture.Fixture
import io.kmpkt.di.compiler.fixture.InMemoryMessageBus
import io.kmpkt.di.compiler.fixture.LoggingMessageBus
import io.kmpkt.di.compiler.fixture.MessageBus
import io.kmpkt.di.compiler.typeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe

private fun byId(id: String): Argument = Argument.Service(ServiceReference.ById(ServiceId(id)))

private fun deferred(id: String): Argument = Argument.Deferred(ServiceReference.ById(ServiceId(id)), Argument.Deferred.Kind.Lazy)

private fun Fixture.bus(
    id: String,
    inner: Argument? = null,
) = container.service<LoggingMessageBus>(ServiceId(id)).apply { if (inner != null) argument("inner", inner) }

private fun Fixture.inlined(): List<String> =
    container.definitions.values
        .filter { it.inlined }
        .map { it.id.value }

class RemoveUnusedTest :
    FunSpec({
        test("what an entry point reaches stays, directly, transitively and through deferred references; the rest goes") {
            // Arrange
            val fixture = Fixture()
            fixture.bus("root", Argument.Sequence(listOf(byId("direct"), deferred("lazy")))).bind<MessageBus>()
            fixture.bus("direct", byId("transitive"))
            fixture.bus("transitive")
            fixture.bus("lazy")
            fixture.bus("unused", byId("onlyUsedByUnused"))
            fixture.bus("onlyUsedByUnused")
            fixture.container.entryPoint("bus", ServiceKey(typeRef<MessageBus>()))

            // Act
            RemoveUnused().process(fixture.container)

            // Assert
            fixture.container.definitions.keys
                .map { it.value } shouldContainExactly listOf("root", "direct", "transitive", "lazy")
        }

        test("the bindings of a removed service go with it") {
            // Arrange
            val fixture = Fixture()
            fixture.container.service<InMemoryMessageBus>().bind<MessageBus>()
            fixture.container.external<DataSource>()
            fixture.container.entryPoint("dataSource", ServiceKey(typeRef<DataSource>()))

            // Act
            RemoveUnused().process(fixture.container)

            // Assert
            fixture.container.aliases.keys shouldContainExactly setOf(ServiceKey(typeRef<DataSource>()))
        }

        test("without an entry point nothing is reachable") {
            // Arrange
            val fixture = Fixture()
            fixture.bus("a", byId("b"))
            fixture.bus("b")

            // Act
            RemoveUnused().process(fixture.container)

            // Assert
            fixture.container.definitions.shouldBeEmpty()
        }
    })

class InlineSingleUseTest :
    FunSpec({
        test("a service with exactly one consumer is inlined, and so is that consumer's only dependency") {
            // Arrange
            val fixture = Fixture()
            fixture.bus("root", byId("middle")).bind<MessageBus>()
            fixture.bus("middle", byId("leaf"))
            fixture.bus("leaf")
            fixture.container.entryPoint("bus", ServiceKey(typeRef<MessageBus>()))

            // Act
            InlineSingleUse().process(fixture.container)

            // Assert
            fixture.inlined() shouldContainExactly listOf("middle", "leaf")
        }

        test("a service used by two consumers, or twice by one, keeps a property of its own") {
            // Arrange
            val fixture = Fixture()
            fixture.bus("a", byId("shared"))
            fixture.bus("b", byId("shared"))
            fixture.bus("shared")
            fixture.bus("c", Argument.Sequence(listOf(byId("twice"), byId("twice"))))
            fixture.bus("twice")
            fixture.bus("d", Argument.Sequence(listOf(byId("mixed"), deferred("mixed"))))
            fixture.bus("mixed")

            // Act
            InlineSingleUse().process(fixture.container)

            // Assert
            fixture.inlined() shouldBe emptyList()
        }

        test("a deferred reference, a prototype, an external and a prototype consumer all rule inlining out") {
            // Arrange
            val fixture = Fixture()
            fixture.bus("a", deferred("lazy"))
            fixture.bus("lazy")
            fixture.bus("b", byId("prototype"))
            fixture.bus("prototype").prototype()
            fixture.container.service<EventDispatcher>().argument("handlers", byId(ServiceId.of<DataSource>().value))
            fixture.container.external<DataSource>()
            fixture.bus("factory", byId("perInstance")).prototype()
            fixture.bus("perInstance")

            // Act
            InlineSingleUse().process(fixture.container)

            // Assert
            fixture.inlined() shouldBe emptyList()
        }

        test("an entry point is never inlined, and neither is a service nobody uses") {
            // Arrange
            val fixture = Fixture()
            fixture.bus("consumer", byId("exposed"))
            fixture.bus("exposed").bind<MessageBus>()
            fixture.container.entryPoint("bus", ServiceKey(typeRef<MessageBus>()))

            // Act
            InlineSingleUse().process(fixture.container)

            // Assert
            fixture.inlined() shouldBe emptyList()
        }
    })
