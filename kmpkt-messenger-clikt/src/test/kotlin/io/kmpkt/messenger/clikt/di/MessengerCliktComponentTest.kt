package io.kmpkt.messenger.clikt.di

import com.github.ajalt.clikt.core.CliktCommand
import io.kmpkt.bridge.clikt.command.CliktRootCommand
import io.kmpkt.bridge.clikt.di.CliktComponent
import io.kmpkt.di.KmpKtApplication
import io.kmpkt.di.compiler.CollectingDiagnostics
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ContainerCompiler
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.fixture.FakeTypeOracle
import io.kmpkt.di.compiler.fixture.parameter
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.bus.MessengerCatalog
import io.kmpkt.messenger.clikt.ConsumeCommand
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.messenger.di.fixture.declareMessenger
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.worker.WorkLock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.slf4j.Logger

class MessengerCliktComponentTest :
    FunSpec({
        test("registers ConsumeCommand and wires it into CliktRootCommand") {
            val types =
                FakeTypeOracle().apply {
                    declareMessenger()
                    declare<Logger>()
                    declare<ConsumeCommand>(
                        parameter<MessengerCatalog>("catalog"),
                        parameter<Logger>("logger", hasDefault = true),
                        parameter<WorkLock?>("workLock", hasDefault = true),
                        supertypes = listOf(typeRef<CliktCommand>()),
                    )
                    declare<CliktRootCommand>(
                        parameter<String>("name"),
                        parameter<List<CliktCommand>>("commands"),
                        parameter<String?>("defaultCommand", hasDefault = true),
                    )
                }
            val diagnostics = CollectingDiagnostics()
            val builder =
                ContainerBuilder(types, diagnostics).apply {
                    entryPoint("cli", ServiceKey(typeRef<CliktRootCommand>()))
                }
            val compiled =
                ContainerCompiler(
                    components = listOf(MessengerCliktComponent(), MessengerComponent(), CliktComponent()),
                    configurations =
                        listOf(
                            Configuration<KmpKtApplication.Default> {
                                service<Logger>()
                                messenger {
                                    transport<InMemoryTransport>("async")
                                    bus("commands")
                                }
                            },
                        ),
                ).compile(builder)

            diagnostics.errors shouldBe emptyList()
            compiled.shouldNotBeNull()
            val consumeId = compiled.definitions.first { it.type == typeRef<ConsumeCommand>() }.id
            compiled.definition(consumeId).type shouldBe typeRef<ConsumeCommand>()
        }
    })
