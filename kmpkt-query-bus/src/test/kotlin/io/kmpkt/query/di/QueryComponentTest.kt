package io.kmpkt.query.di

import io.kmpkt.di.KmpKtApplication
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.CollectingDiagnostics
import io.kmpkt.di.compiler.CompiledContainer
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ContainerCompiler
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.fixture.FakeTypeOracle
import io.kmpkt.di.compiler.fixture.parameter
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.messenger.di.fixture.declareMessenger
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.transport.SyncTransport
import io.kmpkt.query.Query
import io.kmpkt.query.QueryBus
import io.kmpkt.query.QueryHandler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private class FindList : Query<String>

private class Notice : Message

private class FindListHandler : QueryHandler<FindList, String> {
    override suspend fun handle(query: FindList): String = "ok"
}

private class NoticeHandler : MessageHandler<Notice, Unit> {
    override suspend fun invoke(message: Notice) = Unit
}

private fun byId(id: String): Argument = Argument.Service(ServiceReference.ById(ServiceId(id)))

private class Compilation(
    components: List<Component<*>> = listOf(QueryComponent(), MessengerComponent()),
    configure: ContainerBuilder.() -> Unit = {},
) {
    private val types =
        FakeTypeOracle().apply {
            declareMessenger()
            declare<QueryBus>(parameter<MessageBus>("bus"))
            declare<FindListHandler>(
                supertypes = listOf(typeRef<QueryHandler<FindList, String>>(), typeRef<MessageHandler<FindList, String>>()),
            )
            declare<NoticeHandler>(supertypes = listOf(typeRef<MessageHandler<Notice, Unit>>()))
        }
    private val diagnostics = CollectingDiagnostics()

    val errors: List<String> get() = diagnostics.errors
    val compiled: CompiledContainer? =
        ContainerBuilder(types, diagnostics)
            .apply { entryPoint("queries", ServiceKey(typeRef<QueryBus>())) }
            .let { ContainerCompiler(components, listOf(Configuration<KmpKtApplication.Default>(configure))).compile(it) }

    fun arguments(id: String): Map<String, Argument> = compiled!!.definition(ServiceId(id)).arguments
}

class QueryComponentTest :
    FunSpec({
        test("an application gets a QueryBus over a bus of its own, without configuring anything") {
            // Act
            val compilation = Compilation()

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.compiled
                ?.entryPoints
                ?.single()
                ?.id shouldBe ServiceId.of<QueryBus>()
            compilation.arguments(QueryBus::class.qualifiedName!!) shouldBe mapOf("bus" to byId("messenger.bus.query"))
            compilation.arguments("messenger.bus.query.handle")["policy"] shouldBe literal(HandlerPolicy.SINGLE)
        }

        test("the query bus takes the query handlers and leaves other message handlers alone") {
            // Act
            val compilation =
                Compilation {
                    service<FindListHandler>()
                    service<NoticeHandler>()
                }

            // Assert
            val handlers = compilation.arguments("messenger.bus.query.handlers").getValue("handlers") as Argument.Dictionary
            handlers.entries.map { (_, handlers) -> handlers } shouldContainExactly
                listOf(Argument.Sequence(listOf(byId(FindListHandler::class.qualifiedName!!))))
        }

        test("what the application says about the query bus adds to what the component declared") {
            // Act
            val compilation =
                Compilation {
                    messenger {
                        val sync = transport<SyncTransport>("sync")
                        queryBus {
                            policy = HandlerPolicy.MULTIPLE
                            defaultTransport = sync
                        }
                    }
                }

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.arguments("messenger.bus.query.handle")["policy"] shouldBe literal(HandlerPolicy.MULTIPLE)
        }

        test("it requires the messenger, and says so when that is missing") {
            // Act
            val compilation = Compilation(components = listOf(QueryComponent()))

            // Assert
            compilation.compiled.shouldBeNull()
            compilation.errors.single() shouldBe
                "io.kmpkt.query.di.QueryComponent requires io.kmpkt.messenger.di.MessengerComponent, which is not among the " +
                "components. Put the module that declares it on the processor's classpath - ksp(project(\":…-di\"))."
        }
    })
