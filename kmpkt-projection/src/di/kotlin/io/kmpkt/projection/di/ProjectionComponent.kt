package io.kmpkt.projection.di

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceKey
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.Tag
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.messenger.di.BusRef
import io.kmpkt.messenger.di.MessageHandlerTag
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.projection.ProjectEventHandler
import io.kmpkt.projection.ProjectionExecutor
import io.kmpkt.projection.ProjectionStore
import io.kmpkt.projection.ProjectionTransport
import io.kmpkt.projection.Projector
import io.kmpkt.projection.di.pass.RegisterProjectionsPass
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public object ProjectorTag : Tag

public object ProjectionTransportTag : Tag

public data class ProjectionRegistration(
    public val name: String,
    public val rowType: TypeRef,
    public val batchSize: Int = 100,
    public val retryAfter: Duration = 1.minutes,
)

public class ProjectionOptions {
    public var commandBus: BusRef? = null
    internal val projections = mutableListOf<ProjectionRegistration>()

    public fun projection(
        name: String,
        rowType: TypeRef,
        batchSize: Int = 100,
        retryAfter: Duration = 1.minutes,
    ) {
        projections += ProjectionRegistration(name, rowType, batchSize, retryAfter)
    }

    public inline fun <reified R : Any> projection(
        name: String,
        batchSize: Int = 100,
        retryAfter: Duration = 1.minutes,
    ) {
        projection(name, typeRef<R>(), batchSize, retryAfter)
    }
}

public fun ContainerBuilder.projection(block: ProjectionOptions.() -> Unit) {
    configure(ProjectionComponent::class, block)
}

public class ProjectionComponent : Component<ProjectionOptions> {
    override val requires: Set<KClass<out Component<*>>> = setOf(MessengerComponent::class)

    override fun defaults(): ProjectionOptions = ProjectionOptions()

    override fun ContainerBuilder.prepare() {
        autoconfigure<Projector<*, *>> {
            tag(ProjectorTag)
        }
    }

    override fun ContainerBuilder.register(config: ProjectionOptions) {
        if (config.projections.isEmpty()) return

        val executorBase = typeRef<ProjectionExecutor<*>>()
        val transportBase = typeRef<ProjectionTransport<*>>()
        val handlerBase = typeRef<ProjectEventHandler<*>>()
        val storeBase = typeRef<ProjectionStore<*>>()

        val executors = mutableListOf<RegisteredProjection>()

        config.projections.forEach { registration ->
            val rowArg = listOf(TypeArgument.Of(registration.rowType))
            val executorType = executorBase.copy(arguments = rowArg)
            val transportType = transportBase.copy(arguments = rowArg)
            val handlerType = handlerBase.copy(arguments = rowArg)
            val storeType = storeBase.copy(arguments = rowArg)

            val executorId = ServiceId("projection.executor.${registration.name}")
            service(type = executorType, id = executorId) {
                bind(executorType)
            }
            executors += RegisteredProjection(registration.rowType, executorId)

            service(type = transportType, id = ServiceId("projection.transport.${registration.name}")) {
                tag(ProjectionTransportTag)
                argument("projection", literal(registration.name))
                argument("store", Argument.Service(ServiceReference.ByKey(ServiceKey(storeType))))
                argument("projectors", Argument.Service(ServiceReference.ById(executorId)))
                argument("batchSize", literal(registration.batchSize))
                argument("retryAfter", literal(registration.retryAfter))
            }

            service(type = handlerType, id = ServiceId("projection.handler.${registration.name}")) {
                tag(MessageHandlerTag(bus = config.commandBus?.name ?: "command"))
                argument("projection", literal(registration.name))
                argument("store", Argument.Service(ServiceReference.ByKey(ServiceKey(storeType))))
                argument("projectors", Argument.Service(ServiceReference.ById(executorId)))
            }
        }

        addPass(RegisterProjectionsPass(executors))
    }
}

internal data class RegisteredProjection(
    val rowType: TypeRef,
    val executorId: ServiceId,
)
