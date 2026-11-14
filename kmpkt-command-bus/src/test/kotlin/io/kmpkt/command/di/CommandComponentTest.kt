package io.kmpkt.command.di

import io.kmpkt.command.Command
import io.kmpkt.command.CommandBus
import io.kmpkt.command.CommandHandler
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private class Rename : Command

private class Notice : Message

private class RenameHandler : CommandHandler<Rename> {
    override suspend fun handle(command: Rename) = Unit
}

private class NoticeHandler : MessageHandler<Notice, Unit> {
    override suspend fun invoke(message: Notice) = Unit
}

private fun byId(id: String): Argument = Argument.Service(ServiceReference.ById(ServiceId(id)))

private class Compilation(
    components: List<Component<*>> = listOf(CommandComponent(), MessengerComponent()),
    configure: ContainerBuilder.() -> Unit = {},
) {
    private val types =
        FakeTypeOracle().apply {
            declareMessenger()
            declare<CommandBus>(parameter<MessageBus>("bus"))
            declare<RenameHandler>(
                supertypes = listOf(typeRef<CommandHandler<Rename>>(), typeRef<MessageHandler<Rename, Unit>>()),
            )
            declare<NoticeHandler>(supertypes = listOf(typeRef<MessageHandler<Notice, Unit>>()))
        }
    private val diagnostics = CollectingDiagnostics()

    val errors: List<String> get() = diagnostics.errors
    val compiled: CompiledContainer? =
        ContainerBuilder(types, diagnostics)
            .apply { entryPoint("commands", ServiceKey(typeRef<CommandBus>())) }
            .let { ContainerCompiler(components, listOf(Configuration<KmpKtApplication.Default>(configure))).compile(it) }

    fun arguments(id: String): Map<String, Argument> = compiled!!.definition(ServiceId(id)).arguments
}

class CommandComponentTest :
    FunSpec({
        test("an application gets a CommandBus over a bus of its own, without configuring anything") {
            // Act
            val compilation = Compilation()

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.compiled
                ?.entryPoints
                ?.single()
                ?.id shouldBe ServiceId.of<CommandBus>()
            compilation.arguments(CommandBus::class.qualifiedName!!) shouldBe mapOf("bus" to byId("messenger.bus.command"))
            compilation.arguments("messenger.bus.command.handle")["policy"] shouldBe literal(HandlerPolicy.SINGLE)
        }

        test("the command bus takes the command handlers and leaves other message handlers alone") {
            // Act
            val compilation =
                Compilation {
                    service<RenameHandler>()
                    service<NoticeHandler>()
                }

            // Assert
            val handlers = compilation.arguments("messenger.bus.command.handlers").getValue("handlers") as Argument.Dictionary
            handlers.entries.map { (_, handlers) -> handlers } shouldContainExactly
                listOf(Argument.Sequence(listOf(byId(RenameHandler::class.qualifiedName!!))))
        }

        test("what the application says about the command bus adds to what the component declared") {
            // Act
            val compilation =
                Compilation {
                    messenger {
                        val sync = transport<SyncTransport>("sync")
                        commandBus {
                            policy = HandlerPolicy.MULTIPLE
                            defaultTransport = sync
                        }
                    }
                }

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.arguments("messenger.bus.command.handle")["policy"] shouldBe literal(HandlerPolicy.MULTIPLE)
        }

        test("it requires the messenger, and says so when that is missing") {
            // Act
            val compilation = Compilation(components = listOf(CommandComponent()))

            // Assert
            compilation.compiled.shouldBeNull()
            compilation.errors.single() shouldBe
                "io.kmpkt.command.di.CommandComponent requires io.kmpkt.messenger.di.MessengerComponent, which is not among the " +
                "components. Put the module that declares it on the processor's classpath - ksp(project(\":…-di\"))."
        }
    })
