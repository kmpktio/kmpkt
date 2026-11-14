package io.kmpkt.messenger.di.pass

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.TaggedService
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.di.compiler.TypedArgument
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.di.HandlerRegistryTag
import io.kmpkt.messenger.di.MessageHandlerTag
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import kotlin.reflect.KClass

/**
 * Gives each bus's registry its handlers, keyed by the message type each one declares in
 * `MessageHandler<M, R>`.
 *
 * Handlers of one message are called in the order of their [MessageHandlerTag.priority], highest first, and
 * in registration order among equals.
 */
public class RegisterMessageHandlersPass : BuilderPass {
    override fun process(container: ContainerBuilder) {
        val registries = container.tagged<HandlerRegistryTag>()
        // Sorted here, and stably, rather than relied upon: the order handlers of one message are called in
        // is this pass's promise, whatever order the container hands tagged services out in.
        val handlers = container.tagged<MessageHandlerTag>().sortedByDescending { it.tag.priority }
        val buses = registries.map { it.tag.bus }

        handlers
            .filter { it.tag.bus != null && it.tag.bus !in buses }
            .forEach {
                container.diagnostics.error(
                    "${it.definition.describe()} handles messages on the bus \"${it.tag.bus}\", which is not configured. " +
                        "There is: ${buses.joinToString().ifEmpty { "none" }}.",
                )
            }

        val messages = handlers.associate { it.definition.id to messageOf(container, it.definition) }

        registries.forEach { registry ->
            val routes =
                handlers
                    .filter { isOn(container, it, registry.tag) }
                    .groupBy { messages.getValue(it.definition.id) }
                    .mapNotNull { (message, handlers) -> message?.let { entry(it, handlers) } }

            // By the parameter's type, not its name: the registry may call it what it likes.
            container.update(registry.definition.id) {
                it.copy(typedArguments = it.typedArguments + TypedArgument(HANDLERS, Argument.Dictionary(routes)))
            }
        }
    }

    /** A handler that names a bus is on that bus. One that does not is on every bus that takes its kind. */
    private fun isOn(
        container: ContainerBuilder,
        handler: TaggedService<MessageHandlerTag>,
        bus: HandlerRegistryTag,
    ): Boolean =
        when {
            handler.tag.bus != null -> handler.tag.bus == bus.bus
            bus.handles.isEmpty() -> true
            else -> bus.handles.any { container.types.isAssignable(handler.definition.type, it) }
        }

    private fun entry(
        message: TypeRef,
        handlers: List<TaggedService<MessageHandlerTag>>,
    ): Pair<Argument, Argument> =
        Argument.Literal(ConstValue.ClassLiteral(message)) to
            Argument.Sequence(handlers.map { Argument.Service(ServiceReference.ById(it.definition.id)) })

    private fun messageOf(
        container: ContainerBuilder,
        handler: ServiceDefinition,
    ): TypeRef? {
        val message = container.types.typeArguments(handler.type, of = typeRef<MessageHandler<*, *>>()).firstOrNull()

        if (message !is TypeArgument.Of) {
            container.diagnostics.error(
                "Cannot tell which message ${handler.describe()} handles: its type ${handler.type} does not say what the M " +
                    "of MessageHandler<M, R> is.",
            )
        }

        return (message as? TypeArgument.Of)?.type
    }

    private companion object {
        /** What a handler registry takes its handlers as. */
        val HANDLERS = typeRef<Map<KClass<out Message>, List<MessageHandler<*, *>>>>()
    }
}
