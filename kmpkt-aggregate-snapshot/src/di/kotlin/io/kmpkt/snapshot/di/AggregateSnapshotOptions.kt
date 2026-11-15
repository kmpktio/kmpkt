package io.kmpkt.snapshot.di

import io.kmpkt.aggregate.di.AggregateRegistration
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.ContainerDsl
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.di.compiler.literal
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.snapshot.strategy.SnapshotStrategy
import io.kmpkt.snapshot.strategy.VersionIntervalSnapshotStrategy

@ContainerDsl
public class AggregateSnapshotOptions {
    internal var strategy: SnapshotStrategyDeclaration? = null
        private set

    internal val perAggregate: MutableMap<TypeRef, SnapshotStrategyDeclaration> = LinkedHashMap()

    @PublishedApi
    internal fun <S : SnapshotStrategy> strategy(
        type: TypeRef,
        configure: SnapshotStrategyOptions<S>.() -> Unit,
    ) {
        strategy = SnapshotStrategyDeclaration(type, SnapshotStrategyOptions<S>().apply(configure).arguments)
    }

    @PublishedApi
    internal fun forAggregate(
        aggregateType: TypeRef,
        declaration: SnapshotStrategyDeclaration,
    ) {
        perAggregate[aggregateType] = declaration
    }

    /**
     * Selects and configures the default [SnapshotStrategy] [S] used by
     * [SnapshotAggregateRepository][io.kmpkt.snapshot.repository.SnapshotAggregateRepository]
     * when an aggregate has no aggregate-specific strategy.
     */
    public inline fun <reified S : SnapshotStrategy> strategy(noinline configure: SnapshotStrategyOptions<S>.() -> Unit = {}) {
        strategy(typeRef<S>(), configure)
    }
}

/**
 * Configures a [SnapshotStrategy] [S] for use with [withSnapshots]:
 *
 * ```kotlin
 * aggregate {
 *     aggregate<ManagedList>("managed_list") withSnapshots strategy<VersionIntervalSnapshotStrategy> {
 *         interval = 50
 *     }
 * }
 * ```
 */
public inline fun <reified S : SnapshotStrategy> strategy(
    noinline configure: SnapshotStrategyOptions<S>.() -> Unit = {},
): SnapshotStrategyDeclaration = strategyDeclaration(typeRef<S>(), configure)

@PublishedApi
internal fun <S : SnapshotStrategy> strategyDeclaration(
    type: TypeRef,
    configure: SnapshotStrategyOptions<S>.() -> Unit,
): SnapshotStrategyDeclaration = SnapshotStrategyDeclaration(type, SnapshotStrategyOptions<S>().apply(configure).arguments)

/**
 * Enables snapshotting for this aggregate using [strategy].
 */
public infix fun AggregateRegistration.withSnapshots(
    strategy: SnapshotStrategyDeclaration,
): AggregateRegistration =
    apply {
        val targetAggregate = aggregateType
        extend {
            aggregateSnapshot {
                forAggregate(targetAggregate, strategy)
            }
        }
    }

/**
 * Enables snapshotting for this aggregate using [SnapshotStrategy] [S].
 */
public inline fun <reified S : SnapshotStrategy> AggregateRegistration.snapshots(
    noinline configure: SnapshotStrategyOptions<S>.() -> Unit = {},
) {
    withSnapshots(strategy(configure))
}

public class SnapshotStrategyDeclaration internal constructor(
    internal val type: TypeRef,
    internal val arguments: Map<String, Argument>,
)

@ContainerDsl
public class SnapshotStrategyOptions<S : SnapshotStrategy> internal constructor() {
    private val values = LinkedHashMap<String, Any>()

    internal val arguments: Map<String, Argument>
        field = LinkedHashMap<String, Argument>()

    /** Records [value] for the constructor parameter [parameter], to be generated as [argument]. */
    public fun set(
        parameter: String,
        value: Any,
        argument: Argument,
    ) {
        values[parameter] = value
        arguments[parameter] = argument
    }

    /** What was set for [parameter] in this block. Reading what was not set is a mistake: the default lives in the strategy. */
    @Suppress("UNCHECKED_CAST")
    public fun <V> get(parameter: String): V =
        values[parameter] as V? ?: throw IllegalStateException("\"$parameter\" was not set here; the strategy's own default applies")
}

public var SnapshotStrategyOptions<VersionIntervalSnapshotStrategy>.interval: Int
    get() = get("interval")
    set(value) = set("interval", value, literal(value))
