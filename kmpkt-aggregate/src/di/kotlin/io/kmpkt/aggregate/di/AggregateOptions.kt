package io.kmpkt.aggregate.di

import io.kmpkt.aggregate.model.AggregateRoot
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ContainerDsl
import io.kmpkt.di.compiler.Tag
import io.kmpkt.di.compiler.TypeRef
import kotlin.reflect.KClass

@ContainerDsl
public class AggregateOptions {
    internal val aggregates = mutableSetOf<AggregateRegistration>()
    internal val configurators = mutableListOf<ContainerBuilder.() -> Unit>()

    public fun aggregate(
        type: KClass<out AggregateRoot<*>>,
        name: String? = null,
        block: AggregateRegistration.() -> Unit = {},
    ): AggregateRegistration {
        val qualifiedName =
            requireNotNull(type.qualifiedName) {
                "Local or anonymous aggregate classes are not supported"
            }
        val registration =
            AggregateRegistration(
                aggregateType = TypeRef(qualifiedName),
                name = name ?: qualifiedName.substringAfterLast('.').replaceFirstChar { it.lowercase() },
            ).also { it.options = this }
        aggregates += registration
        registration.block()
        return registration
    }

    public inline fun <reified A : AggregateRoot<*>> aggregate(
        name: String? = null,
        noinline block: AggregateRegistration.() -> Unit = {},
    ): AggregateRegistration = aggregate(A::class, name, block)

    internal fun extend(block: ContainerBuilder.() -> Unit) {
        configurators += block
    }
}

@ContainerDsl
public data class AggregateRegistration(
    val aggregateType: TypeRef,
    val name: String,
) {
    internal var options: AggregateOptions? = null

    public fun extend(block: ContainerBuilder.() -> Unit) {
        checkNotNull(options) { "AggregateRegistration is not attached to AggregateOptions" }.extend(block)
    }
}

public data class RegisteredAggregate(
    val aggregateType: TypeRef,
    val name: String,
) : Tag
