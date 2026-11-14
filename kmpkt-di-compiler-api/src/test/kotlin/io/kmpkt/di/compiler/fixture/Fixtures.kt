package io.kmpkt.di.compiler.fixture

import io.kmpkt.di.compiler.CollectingDiagnostics
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.Tag
import io.kmpkt.di.compiler.typeRef

// What a feature module looks like from here: plain classes that know nothing of the container.

class DataSource

class RetryConfig

interface MessageBus

class InMemoryMessageBus : MessageBus

class JdbcOutboxMessageBus(
    @Suppress("unused") private val dataSource: DataSource,
) : MessageBus

class RetryingMessageBus(
    @Suppress("unused") private val inner: MessageBus,
    @Suppress("unused") private val config: RetryConfig,
) : MessageBus

class LoggingMessageBus(
    @Suppress("unused") private val inner: MessageBus,
) : MessageBus

class InvoicePaid

class OrderShipped

interface DomainEventHandler<E>

class WhenInvoicePaidReleaseShipment : DomainEventHandler<InvoicePaid>

class WhenInvoicePaidNotifyWarehouse : DomainEventHandler<InvoicePaid>

class WhenOrderShippedSendInvoice : DomainEventHandler<OrderShipped>

class EventDispatcher(
    @Suppress("unused") private val handlers: List<DomainEventHandler<*>>,
)

fun createMessageBus(): MessageBus = InMemoryMessageBus()

object MessageBusFactory {
    fun create(): MessageBus = InMemoryMessageBus()
}

enum class TransportType { IN_MEMORY, JDBC_OUTBOX }

data class EventHandlerTag(
    override val priority: Int = 0,
) : Tag

data class OtherTag(
    val label: String = "",
) : Tag

/** A builder over a [FakeTypeOracle] that already knows the fixture classes, and the diagnostics it writes to. */
class Fixture {
    val types = FakeTypeOracle().apply { declareFixtures() }
    val diagnostics = CollectingDiagnostics()
    val container = ContainerBuilder(types, diagnostics)
}

fun FakeTypeOracle.declareFixtures() {
    val bus = listOf(typeRef<MessageBus>())

    declare<DataSource>()
    declare<RetryConfig>()
    declare<InMemoryMessageBus>(supertypes = bus)
    declare<JdbcOutboxMessageBus>(parameter<DataSource>("dataSource"), supertypes = bus)
    declare<RetryingMessageBus>(parameter<MessageBus>("inner"), parameter<RetryConfig>("config"), supertypes = bus)
    declare<LoggingMessageBus>(parameter<MessageBus>("inner"), supertypes = bus)
    declare<WhenInvoicePaidReleaseShipment>(supertypes = listOf(typeRef<DomainEventHandler<InvoicePaid>>()))
    declare<WhenInvoicePaidNotifyWarehouse>(supertypes = listOf(typeRef<DomainEventHandler<InvoicePaid>>()))
    declare<WhenOrderShippedSendInvoice>(supertypes = listOf(typeRef<DomainEventHandler<OrderShipped>>()))
    declare<EventDispatcher>(parameter<List<DomainEventHandler<*>>>("handlers"))
    declareFunction("io.kmpkt.di.compiler.fixture.createMessageBus")
    declareFunction("io.kmpkt.di.compiler.fixture.MessageBusFactory.create")
}
