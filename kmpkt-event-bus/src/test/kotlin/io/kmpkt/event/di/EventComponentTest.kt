package io.kmpkt.event.di

import io.kmpkt.di.KmpKtApplication
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.CollectingDiagnostics
import io.kmpkt.di.compiler.CompiledContainer
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
import io.kmpkt.event.Event
import io.kmpkt.event.EventBus
import io.kmpkt.event.EventHandler
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.messenger.di.fixture.declareMessenger
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private class Renamed : Event

private class Notice : Message

private class FirstListener : EventHandler<Renamed> {
    override suspend fun handle(event: Renamed) = Unit
}

private class SecondListener : EventHandler<Renamed> {
    override suspend fun handle(event: Renamed) = Unit
}

private class NoticeHandler : MessageHandler<Notice, Unit> {
    override suspend fun invoke(message: Notice) = Unit
}

private fun byId(id: String): Argument = Argument.Service(ServiceReference.ById(ServiceId(id)))

private class Compilation(
    configure: ContainerBuilder.() -> Unit = {},
) {
    private val types =
        FakeTypeOracle().apply {
            declareMessenger()
            declare<EventBus>(parameter<MessageBus>("bus"))
            listOf(typeRef<FirstListener>(), typeRef<SecondListener>()).forEach {
                declare(it, supertypes = listOf(typeRef<EventHandler<Renamed>>(), typeRef<MessageHandler<Renamed, Unit>>()))
            }
            declare<NoticeHandler>(supertypes = listOf(typeRef<MessageHandler<Notice, Unit>>()))
        }
    private val diagnostics = CollectingDiagnostics()

    val errors: List<String> get() = diagnostics.errors
    val compiled: CompiledContainer? =
        ContainerBuilder(types, diagnostics)
            .apply { entryPoint("events", ServiceKey(typeRef<EventBus>())) }
            .let {
                ContainerCompiler(
                    listOf(EventComponent(), MessengerComponent()),
                    listOf(Configuration<KmpKtApplication.Default>(configure)),
                ).compile(it)
            }

    fun arguments(id: String): Map<String, Argument> = compiled!!.definition(ServiceId(id)).arguments
}

class EventComponentTest :
    FunSpec({
        test("an application gets an EventBus over a bus of its own that allows any number of handlers") {
            // Act
            val compilation = Compilation()

            // Assert
            compilation.errors.shouldBeEmpty()
            compilation.compiled
                ?.entryPoints
                ?.single()
                ?.id shouldBe ServiceId.of<EventBus>()
            compilation.arguments(EventBus::class.qualifiedName!!) shouldBe mapOf("bus" to byId("messenger.bus.event"))
            compilation.arguments("messenger.bus.event.handle")["policy"] shouldBe literal(HandlerPolicy.MULTIPLE)
        }

        test("the event bus takes every event handler of an event and leaves other message handlers alone") {
            // Act
            val compilation =
                Compilation {
                    service<FirstListener>()
                    service<SecondListener>()
                    service<NoticeHandler>()
                }

            // Assert
            val handlers = compilation.arguments("messenger.bus.event.handlers").getValue("handlers") as Argument.Dictionary
            handlers.entries.map { (_, handlers) -> handlers } shouldContainExactly
                listOf(
                    Argument.Sequence(
                        listOf(byId(FirstListener::class.qualifiedName!!), byId(SecondListener::class.qualifiedName!!)),
                    ),
                )
        }
    })
