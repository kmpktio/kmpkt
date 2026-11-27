package io.kmpkt.messenger.di.fixture

import io.kmpkt.di.compiler.fixture.FakeTypeOracle
import io.kmpkt.di.compiler.fixture.parameter
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.bus.DefaultMessageBus
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.bus.MessengerCatalog
import io.kmpkt.messenger.bus.RoutableMessageBus
import io.kmpkt.messenger.bus.middleware.AddBusNameStampMiddleware
import io.kmpkt.messenger.handler.DefaultHandlerRegistry
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.handler.HandlerRegistry
import io.kmpkt.messenger.handler.middleware.HandleMessageMiddleware
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.middleware.Middleware
import io.kmpkt.messenger.retry.MultiplierRetryStrategy
import io.kmpkt.messenger.retry.RetryStrategy
import io.kmpkt.messenger.retry.middleware.FailedMessageProcessingMiddleware
import io.kmpkt.messenger.retry.middleware.RetryMiddleware
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kmpkt.messenger.transport.SyncTransport
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.messenger.transport.middleware.SendMessageMiddleware
import io.kmpkt.messenger.transport.registry.DefaultTransportRegistry
import io.kmpkt.messenger.transport.registry.TransportRegistry
import io.kmpkt.messenger.transport.routing.DefaultTransportRouter
import io.kmpkt.messenger.transport.routing.TransportRouter
import org.slf4j.Logger
import java.time.Clock
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * Tells a [FakeTypeOracle] what the processor would know about the messenger's own classes, so that a
 * component building on `MessengerComponent` can be compiled in a unit test.
 */
public fun FakeTypeOracle.declareMessenger() {
    declareFunction("java.time.Clock.systemUTC")
    declare<DefaultHandlerRegistry>(
        parameter<Map<KClass<out Message>, List<MessageHandler<*, *>>>>("handlers", hasDefault = true),
        supertypes = listOf(typeRef<HandlerRegistry>()),
    )
    declare<HandleMessageMiddleware>(
        parameter<HandlerRegistry>("handlers"),
        parameter<HandlerPolicy>("policy", hasDefault = true),
        parameter<Clock>("clock"),
        supertypes = listOf(typeRef<Middleware>()),
    )
    declare<DefaultMessageBus>(
        parameter<List<Middleware>>("middleware", hasDefault = true),
        parameter<HandlerRegistry>("handlerRegistry"),
        supertypes = listOf(typeRef<MessageBus>()),
    )
    declare<RoutableMessageBus>(
        parameter<Map<String, MessageBus>>("buses"),
        parameter<MessageBus?>("fallback", hasDefault = true),
        supertypes = listOf(typeRef<MessageBus>()),
    )
    declare<MessengerCatalog>(
        parameter<Map<String, MessageBus>>("buses"),
        parameter<Map<String, Transport>>("transports"),
        parameter<Map<String, Transport>>("failureTransports", hasDefault = true),
        parameter<MessageBus?>("defaultBus", hasDefault = true),
    )
    declare<SyncTransport>(
        parameter<Lazy<MessageBus>>("messageBus"),
        parameter<String>("name"),
        parameter<RetryStrategy?>("retryStrategy", hasDefault = true),
        parameter<Transport?>("failureTransport", hasDefault = true),
        supertypes = listOf(typeRef<Transport>()),
    )
    declare<InMemoryTransport>(parameter<Clock>("clock", hasDefault = true), supertypes = listOf(typeRef<Transport>()))
    declare<DefaultTransportRegistry>(
        parameter<Map<String, Transport>>("transports", hasDefault = true),
        supertypes = listOf(typeRef<TransportRegistry>()),
    )
    declare<DefaultTransportRouter>(
        parameter<TransportRegistry>("transportRegistry"),
        parameter<Map<KClass<out Message>, String>>("routes", hasDefault = true),
        parameter<String?>("fallback", hasDefault = true),
        supertypes = listOf(typeRef<TransportRouter>()),
    )
    declare<SendMessageMiddleware>(
        parameter<TransportRouter>("transportRouter"),
        parameter<Clock>("clock"),
        supertypes = listOf(typeRef<Middleware>()),
    )
    declare<AddBusNameStampMiddleware>(
        parameter<String>("busName"),
        supertypes = listOf(typeRef<Middleware>()),
    )
    declare<FailedMessageProcessingMiddleware>(
        parameter<Collection<String>>("failureTransportNames"),
        supertypes = listOf(typeRef<Middleware>()),
    )
    declare<RetryMiddleware>(
        parameter<Map<String, RetryStrategy>>("strategies"),
        parameter<Map<String, Transport>>("transports"),
        parameter<Logger>("logger", hasDefault = true),
        supertypes = listOf(typeRef<Middleware>()),
    )
    declare<MultiplierRetryStrategy>(
        parameter<Int>("maxRetries", hasDefault = true),
        parameter<Duration>("delay", hasDefault = true),
        parameter<Double>("multiplier", hasDefault = true),
        parameter<Duration>("maxDelay", hasDefault = true),
        parameter<Double>("jitter", hasDefault = true),
        supertypes = listOf(typeRef<RetryStrategy>()),
    )
}
