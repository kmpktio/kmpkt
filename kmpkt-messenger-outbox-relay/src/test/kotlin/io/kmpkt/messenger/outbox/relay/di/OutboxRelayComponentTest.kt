package io.kmpkt.messenger.outbox.relay.di

import io.kmpkt.di.KmpKtApplication
import io.kmpkt.di.compiler.CollectingDiagnostics
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ContainerCompiler
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.fixture.FakeTypeOracle
import io.kmpkt.di.compiler.fixture.parameter
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.messenger.di.TransportRef
import io.kmpkt.messenger.di.fixture.declareMessenger
import io.kmpkt.messenger.di.messenger
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.outbox.relay.OutboxRelayMiddleware
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.messenger.transport.Transport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class OutboxRelayComponentTest :
    FunSpec({
        fun compile(configure: ContainerBuilder.() -> Unit) =
            run {
                val types =
                    FakeTypeOracle().apply {
                        declareMessenger()
                        declare<OutboxRelayMiddleware>(
                            parameter<String>("sourceName"),
                            parameter<Transport>("target"),
                            supertypes = listOf(typeRef<Middleware>()),
                        )
                    }
                val diagnostics = CollectingDiagnostics()
                val builder =
                    ContainerBuilder(types, diagnostics).apply {
                        entryPoint("relay", ServiceKey(typeRef<Middleware>(), "outbox-to-nats"))
                    }
                val compiled =
                    ContainerCompiler(
                        components = listOf(OutboxRelayComponent(), MessengerComponent()),
                        configurations = listOf(Configuration<KmpKtApplication.Default>(configure)),
                    ).compile(builder)
                diagnostics.errors shouldBe emptyList()
                compiled.shouldNotBeNull()
            }

        test("registers OutboxRelayMiddleware bound as Middleware under <from>-to-<to>") {
            val container =
                compile {
                    var outbox: TransportRef<InMemoryTransport>? = null
                    var nats: TransportRef<InMemoryTransport>? = null
                    messenger {
                        outbox = transport<InMemoryTransport>("outbox")
                        nats = transport<InMemoryTransport>("nats")
                    }
                    outboxRelay {
                        relay(from = outbox!!, to = nats!!)
                    }
                }

            val relay = container.definition(ServiceId("outbox.relay.outbox-to-nats"))
            relay.type shouldBe typeRef<OutboxRelayMiddleware>()
        }
    })
