package io.kmpkt.di.compiler

import io.kmpkt.di.KmpKtApplication
import kotlin.reflect.KClass

/**
 * Contributes a feature module's services, configuration options, and [BuilderPass]es to the container.
 *
 * Components are discovered automatically via `META-INF/services` from `-di` companion modules and
 * executed in topological order based on [requires]:
 *
 * 1. [defaults] creates the initial options object [C] for each component.
 * 2. [prepare] runs before the application's [Configuration], allowing a component to pre-configure
 *    options of components it [requires].
 * 3. The application's [Configuration] runs and overrides any prepared or default options.
 * 4. [register] registers services and [BuilderPass]es against the resolved [config].
 * 5. Registered [BuilderPass]es execute once all components and the application have registered
 *    their definitions.
 */
public interface Component<C : Any> {
    /**
     * Components that must be present on the processor classpath and execute before this one.
     *
     * Missing dependencies fail compilation with a diagnostic naming the missing component.
     */
    public val requires: Set<KClass<out Component<*>>> get() = emptySet()

    /**
     * Creates the initial options object [C] before any [prepare] blocks or the application's
     * [Configuration] run.
     */
    public fun defaults(): C

    /**
     * Pre-configures options of components listed in [requires].
     *
     * Runs before the application's [Configuration] so user-provided configuration always takes
     * precedence.
     */
    public fun ContainerBuilder.prepare() {}

    /**
     * Registers services and [BuilderPass]es using the resolved [config].
     *
     * Runs on an isolated [ContainerBuilder] that is merged into the main container afterwards;
     * definitions registered directly by the application always take precedence.
     */
    public fun ContainerBuilder.register(config: C)
}

/**
 * Convenience base interface for a [Component] that requires no configuration options.
 */
public interface SimpleComponent : Component<Unit> {
    override fun defaults(): Unit = Unit

    /**
     * Registers services and [BuilderPass]es on the receiver [ContainerBuilder].
     */
    public fun ContainerBuilder.register() {}

    override fun ContainerBuilder.register(config: Unit): Unit = register()
}

/**
 * The application's own compile-time setup for [A]: options for components, and definitions that
 * override theirs. Lives in a unit compiled before the application module - a `di` source set or
 * module - because the processor has to run it. Found the same way a [Component] is.
 *
 * ```kotlin
 * class TodoConfiguration : Configuration<TodoApp> {
 *     override fun ContainerBuilder.configure() {
 *         messenger { … }
 *         external<ListSource>()
 *     }
 * }
 * ```
 */
public fun interface Configuration<A : KmpKtApplication> {
    public fun ContainerBuilder.configure()
}
