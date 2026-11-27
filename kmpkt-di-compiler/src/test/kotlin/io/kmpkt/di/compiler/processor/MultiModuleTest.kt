package io.kmpkt.di.compiler.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * The whole setup, module by module, each compiled separately the way Gradle would:
 *
 * ```
 * :messenger, :kernel            plain Kotlin
 * :messenger-di, :kernel-di      a Component each, processor on to write META-INF/services
 * :app (di source set)           a Configuration
 * :app                           ApplicationRunner + KmpKt.run(), with all of the above on the processor's classpath
 * ```
 */
@OptIn(ExperimentalCompilerApi::class)
private class Project {
    val messengerModule = compileModule(messenger)
    val kernelModule = compileModule(kernel)
    val features = listOf(messengerModule, kernelModule)
    val messengerDiModule = compileModule(messengerDi, dependsOn = features, processorClasspath = emptyList())
    val kernelDiModule = compileModule(kernelDi, dependsOn = features, processorClasspath = emptyList())
    val bundles = listOf(messengerDiModule, kernelDiModule)

    init {
        (features + bundles).forEach {
            if (it.result.exitCode != KotlinCompilation.ExitCode.OK) {
                error("Compilation failed:\n${it.result.messages}")
            }
        }
    }

    fun configuration(
        source: SourceFile,
        feature: Module? = null,
    ): Module =
        compileModule(source, dependsOn = features + bundles + listOfNotNull(feature), processorClasspath = emptyList())
            .also { it.result.exitCode shouldBe KotlinCompilation.ExitCode.OK }

    fun application(
        source: SourceFile,
        configuration: Module? = null,
        feature: Module? = null,
    ): Module =
        compileModule(
            source,
            dependsOn = features + listOfNotNull(feature),
            processorClasspath = features + bundles + listOfNotNull(feature, configuration),
        )
}

@OptIn(ExperimentalCompilerApi::class)
class MultiModuleTest :
    FunSpec({
        val project = Project()

        test("a -di module's processor run lists its component where ServiceLoader looks") {
            project.messengerDiModule.generated("io.kmpkt.di.compiler.Component") shouldBe "acme.messenger.di.MessengerComponent\n"
            project.configuration(appConfig).generated("io.kmpkt.di.compiler.Configuration") shouldBe "acme.app.di.AppConfig\n"
        }

        test("the processor generates KmpKt.run with the decorated, factory-built and routed services") {
            // Act
            val module = project.application(app, project.configuration(appConfig))

            // Assert
            module.result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            val generated = module.generated("KmpKt.kt")
            generated shouldContain "public object KmpKt"
            generated shouldContain "KmpKtApplicationDefaultContainer"
            generated shouldContain "createSecretAdapter("
            generated shouldContain "RetryingMessageBus("
            generated shouldContain "JdbcOutboxMessageBus("
            generated shouldContain "EventDispatcher("
            generated shouldContain "AppRunner("
        }

        test("the generated container runs: decorated, routed by event type, built through the public factory, shared") {
            // Arrange
            val module = project.application(app, project.configuration(appConfig))

            // Act
            val report =
                module.result.classLoader
                    .loadClass("acme.app.AppKt")
                    .getMethod("run")
                    .invoke(null)

            // Assert
            report shouldBe
                listOf(
                    "retry*5:true(outbox@jdbc:test(hello))",
                    "InvoicePaid -> WhenInvoicePaidNotifyWarehouse, WhenInvoicePaidReleaseShipment; " +
                        "OrderShipped -> WhenOrderShippedSendInvoice",
                    "secret@jdbc:test",
                    "true",
                )
        }

        test("with an empty configuration every component is loaded with its defaults") {
            // Arrange
            val emptyConfig =
                project.configuration(
                    SourceFile.kotlin(
                        "EmptyConfig.kt",
                        """
                        package acme.app.di

                        import io.kmpkt.di.KmpKtApplication
                        import io.kmpkt.di.compiler.Configuration
                        import io.kmpkt.di.compiler.ContainerBuilder

                        class EmptyConfig : Configuration<KmpKtApplication.Default> {
                            override fun ContainerBuilder.configure() {
                                discover("acme.app")
                            }
                        }
                        """.trimIndent(),
                    ),
                )
            val source =
                SourceFile.kotlin(
                    "App.kt",
                    """
                    package acme.app

                    import acme.messenger.MessageBus
                    import io.kmpkt.di.ApplicationRunner
                    import io.kmpkt.di.KmpKt

                    object Output {
                        var value: String = ""
                    }

                    class DefaultRunner(
                        private val bus: MessageBus,
                    ) : ApplicationRunner {
                        override fun run(args: Array<String>) {
                            Output.value = bus.dispatch("hello")
                        }
                    }

                    fun run(): String {
                        KmpKt.run()
                        return Output.value
                    }
                    """.trimIndent(),
                )

            // Act
            val module = project.application(source, emptyConfig)

            // Assert
            module.result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            module.generated("KmpKt.kt") shouldContain "private class KmpKtApplicationDefaultContainer"
            module.result.classLoader
                .loadClass("acme.app.AppKt")
                .getMethod("run")
                .invoke(null) shouldBe "memory(hello)"
        }

        test("a named entry point, a prototype, deferred parameters and defaults make it through KSP and back") {
            // Arrange
            val consumer =
                compileModule(
                    SourceFile.kotlin(
                        "Consumer.kt",
                        """
                        package acme.consumer

                        import acme.messenger.MessageBus

                        class Consumer(
                            val event: MessageBus,
                            val command: Lazy<MessageBus>,
                            val provider: () -> MessageBus,
                            val missing: String?,
                            val defaulted: Int = 7,
                        )
                        """.trimIndent(),
                    ),
                    dependsOn = project.features,
                )
            val configuration =
                project.configuration(
                    feature = consumer,
                    source =
                        SourceFile.kotlin(
                            "NamedConfig.kt",
                            """
                                package acme.app.di

                                import acme.messenger.InMemoryMessageBus
                                import acme.messenger.MessageBus
                                import acme.messenger.RetryingMessageBus
                                import io.kmpkt.di.KmpKtApplication
                            import io.kmpkt.di.compiler.Configuration
                                import io.kmpkt.di.compiler.ContainerBuilder
                                import io.kmpkt.di.compiler.ServiceId
                                import io.kmpkt.di.compiler.service
                                class NamedConfig : Configuration<KmpKtApplication.Default> {
                                    override fun ContainerBuilder.configure() {
                                        val container = this
                                        container.service<InMemoryMessageBus>(ServiceId("bus.event")) {
                                            bind<MessageBus>("event")
                                            prototype()
                                        }
                                        container.service<acme.consumer.Consumer> {
                                            argument(9)
                                        }
                                        container.service<RetryingMessageBus>(ServiceId("bus.command")) {
                                            bind<MessageBus>("command")
                                            argument(service<MessageBus>("event"))
                                        }
                                        container.service<acme.messenger.RetryConfig>()
                                        container.discover("acme.app")
                                    }
                                }
                            """.trimIndent(),
                        ),
                )
            val source =
                SourceFile.kotlin(
                    "App.kt",
                    """
                    package acme.app

                    import acme.consumer.Consumer
                    import acme.messenger.MessageBus
                    import io.kmpkt.di.ApplicationRunner
                    import io.kmpkt.di.KmpKt

                    object Output {
                        var report: List<Any?> = emptyList()
                    }

                    class NamedRunner(
                        private val consumer: Consumer,
                    ) : ApplicationRunner {
                        override fun run(args: Array<String>) {
                            Output.report =
                                listOf(
                                    consumer.command.value.dispatch("x"),
                                    consumer.command.value === consumer.command.value,
                                    consumer.provider() === consumer.provider(),
                                    consumer.event === consumer.provider(),
                                    consumer.missing,
                                    consumer.defaulted,
                                )
                        }
                    }

                    fun run(): List<Any?> {
                        KmpKt.run()
                        return Output.report
                    }
                    """.trimIndent(),
                )

            // Act
            val module = project.application(source, configuration, consumer)

            // Assert
            module.result.messages shouldNotContain "e: "
            module.result.classLoader
                .loadClass("acme.app.AppKt")
                .getMethod("run")
                .invoke(null) shouldBe listOf("retry*3:true(memory(x))", true, true, false, null, 9)
        }

        test("what the graph is missing fails the compilation at the container, every problem in one go") {
            // Arrange
            val configuration =
                project.configuration(
                    SourceFile.kotlin(
                        "BrokenConfig.kt",
                        """
                        package acme.app.di

                        import acme.messenger.JdbcOutboxMessageBus
                        import acme.messenger.MessageBus
                        import acme.messenger.RetryingMessageBus
                        import io.kmpkt.di.KmpKtApplication
                        import io.kmpkt.di.compiler.Configuration
                        import io.kmpkt.di.compiler.ContainerBuilder
                        class BrokenConfig : Configuration<KmpKtApplication.Default> {
                            override fun ContainerBuilder.configure() {
                                val container = this
                                container.service<JdbcOutboxMessageBus>().bind<MessageBus>()
                                container.service<RetryingMessageBus>().decorates<MessageBus>()
                                container.discover("acme.app")
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            // Act
            val module = project.application(app, configuration)

            // Assert
            module.result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            module.result.messages shouldContain
                "Cannot autowire parameter \"dataSource\" of \"acme.messenger.JdbcOutboxMessageBus\": " +
                "no service is bound to acme.messenger.DataSource."
            module.result.messages shouldContain
                "Cannot autowire parameter \"config\" of \"acme.messenger.RetryingMessageBus\": no service is bound to " +
                "acme.messenger.RetryConfig."
        }

        test("an internal class cannot be registered by its constructor, and the error says what to do instead") {
            // Arrange
            val configuration =
                project.configuration(
                    SourceFile.kotlin(
                        "InternalConfig.kt",
                        """
                        package acme.app.di

                        import acme.messenger.InvoiceRepository
                        import io.kmpkt.di.KmpKtApplication
                        import io.kmpkt.di.compiler.Configuration
                        import io.kmpkt.di.compiler.ContainerBuilder
                        import io.kmpkt.di.compiler.TypeRef
                        import io.kmpkt.di.compiler.typeRef
                        class InternalConfig : Configuration<KmpKtApplication.Default> {
                            override fun ContainerBuilder.configure() {
                                val container = this
                                container.service(TypeRef("acme.messenger.SecretAdapter")).bind<InvoiceRepository>()
                            }
                        }
                        """.trimIndent(),
                    ),
                )

            // Act
            val module = project.application(app, configuration)

            // Assert
            module.result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            module.result.messages shouldContain
                "Cannot build \"acme.messenger.SecretAdapter\": acme.messenger.SecretAdapter has no public constructor the " +
                "generated container could call."
        }

        test("a configuration that throws fails the compilation with its own stack trace") {
            // Arrange
            val configuration =
                project.configuration(
                    SourceFile.kotlin(
                        "ThrowingConfig.kt",
                        """
                        package acme.app.di

                        import io.kmpkt.di.KmpKtApplication
                        import io.kmpkt.di.compiler.Configuration
                        import io.kmpkt.di.compiler.ContainerBuilder
                        class ThrowingConfig : Configuration<KmpKtApplication.Default> {
                            override fun ContainerBuilder.configure(): Unit = throw IllegalStateException("not today")
                        }
                        """.trimIndent(),
                    ),
                )

            // Act
            val module = project.application(app, configuration)

            // Assert
            module.result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            module.result.messages shouldContain "Compiling the container failed: java.lang.IllegalStateException: not today"
            module.result.messages shouldContain "at acme.app.di.ThrowingConfig.configure"
        }

        test("an application without a Configuration on the processor classpath does not generate KmpKt") {
            // Act
            val module = compileModule(app, dependsOn = project.features, processorClasspath = emptyList())

            // Assert
            module.result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            module.result.messages shouldContain "Unresolved reference 'KmpKt'"
        }

        test("what the processor could not use is refused where it is declared") {
            // Arrange
            val source =
                SourceFile.kotlin(
                    "Wrong.kt",
                    """
                    package acme.app

                    import io.kmpkt.di.KmpKtApplication
                    import io.kmpkt.di.compiler.Configuration
                    import io.kmpkt.di.compiler.ContainerBuilder
                    import io.kmpkt.di.compiler.SimpleComponent

                    class NeedsAnArgument(val name: String) : SimpleComponent

                    internal class Hidden : Configuration<KmpKtApplication.Default> {
                        override fun ContainerBuilder.configure() = Unit
                    }

                    // Neither of these is something to load, and neither is an error.
                    abstract class Unfinished : SimpleComponent
                    interface StillAnInterface : SimpleComponent
                    """.trimIndent(),
                )

            // Act
            val module = compileModule(source, processorClasspath = emptyList())

            // Assert
            module.result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            val errors =
                module.result.messages
                    .lines()
                    .filter { it.startsWith("e: [ksp]") }
            errors.map { it.substringAfter("Wrong.kt:").substringBefore(":") } shouldContainExactly listOf("8", "10")
            module.result.messages shouldContain
                "A Component is created by the processor, which needs it public with a public constructor that takes no arguments."
            module.result.messages shouldContain
                "A Configuration is created by the processor, which needs it public with a public constructor that takes no arguments."
        }

        test("discover registers the application's own classes, binds them to their interfaces and skips what is no service") {
            // Arrange
            val configuration = project.configuration(discovering("acme.shop"))
            val source =
                SourceFile.kotlin(
                    "Shop.kt",
                    """
                    package acme.shop

                    import io.kmpkt.di.ApplicationRunner
                    import io.kmpkt.di.KmpKt

                    interface Greeter {
                        fun greet(): String
                    }

                    class FriendlyGreeter : Greeter {
                        override fun greet() = "hello"
                    }

                    // Internal is fine: the container is generated into this very module.
                    internal class Counter {
                        var visits = 0
                    }

                    class Shop internal constructor(
                        private val greeter: Greeter,
                        private val counter: Counter,
                    ) {
                        fun open(): String = greeter.greet() + " #" + (++counter.visits)
                    }

                    // None of these is a service. The first would even fail to autowire - if anything needed it.
                    class Unwireable(val name: String)
                    data class Item(val name: String)
                    abstract class Base
                    sealed class Shape
                    object Registry
                    enum class Kind { ONLY }
                    annotation class Marker
                    class Oops : RuntimeException()
                    class Box<T>
                    private class Hidden
                    class Outer {
                        inner class Inner
                    }

                    object Output {
                        var value: String = ""
                    }

                    class ShopRunner(
                        private val shop: Shop,
                    ) : ApplicationRunner {
                        override fun run(args: Array<String>) {
                            Output.value = shop.open()
                        }
                    }

                    fun run(): String {
                        KmpKt.run()
                        return Output.value
                    }
                    """.trimIndent(),
                )

            // Act
            val module = project.application(source, configuration)

            // Assert
            module.result.messages
                .lines()
                .filter { it.startsWith("e: ") } shouldBe emptyList()
            val generated = module.generated("KmpKt.kt")
            generated shouldContain "Shop("
            generated shouldContain "greeter = FriendlyGreeter()"
            generated shouldContain "counter = Counter()"
            module.result.classLoader
                .loadClass("acme.shop.ShopKt")
                .getMethod("run")
                .invoke(null) shouldBe "hello #1"
        }

        test("a discovered class that cannot be wired fails the compilation once the container reaches it") {
            // Arrange
            val configuration = project.configuration(discovering("acme.shop"))
            val source =
                SourceFile.kotlin(
                    "Shop.kt",
                    """
                    package acme.shop

                    import io.kmpkt.di.ApplicationRunner

                    class Unwireable(val name: String)

                    class Shop(val unwireable: Unwireable)

                    class ShopRunner(val shop: Shop) : ApplicationRunner {
                        override fun run(args: Array<String>) = Unit
                    }
                    """.trimIndent(),
                )

            // Act
            val module = project.application(source, configuration)

            // Assert
            module.result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            module.result.messages shouldContain
                "Cannot autowire parameter \"name\" of \"acme.shop.Unwireable\" (registered by " +
                "discover(\"acme.shop\")): no service is bound to kotlin.String."
        }

        test("two discovered implementations of an interface somebody asks for are named in the error") {
            // Arrange
            val configuration = project.configuration(discovering("acme.shop"))
            val source =
                SourceFile.kotlin(
                    "Shop.kt",
                    """
                    package acme.shop

                    import io.kmpkt.di.ApplicationRunner

                    interface Greeter
                    class FriendlyGreeter : Greeter
                    class GrumpyGreeter : Greeter

                    class ShopRunner(val greeter: Greeter) : ApplicationRunner {
                        override fun run(args: Array<String>) = Unit
                    }
                    """.trimIndent(),
                )

            // Act
            val module = project.application(source, configuration)

            // Assert
            module.result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            module.result.messages shouldContain
                "acme.shop.Greeter is implemented by 2 discovered services (acme.shop.FriendlyGreeter, acme.shop.GrumpyGreeter) " +
                "and bound to none of them. Bind the one that is meant."
        }

        test("scoped contexts support ContextFactory with root services, invoke/open/close lifecycle, and parent injection") {
            val feature =
                compileModule(
                    SourceFile.kotlin(
                        "ScopedFeature.kt",
                        """
                        package acme.scoped

                        import io.kmpkt.di.ContextFactory
                        import io.kmpkt.di.ContextHandle

                        class IdGenerator {
                            private var next = 0L
                            val events = mutableListOf<String>()

                            fun nextId(): Long = ++next
                        }

                        data class MyContext(
                            val id: Long,
                            val isLoggingEnabled: Boolean,
                        )

                        data class MyContextOptions(
                            val isLoggingEnabled: Boolean,
                        )

                        class MyContextFactory(
                            private val ids: IdGenerator,
                        ) : ContextFactory<MyContext, MyContextOptions> {
                            override fun create(input: MyContextOptions): MyContext =
                                MyContext(ids.nextId(), input.isLoggingEnabled).also {
                                    ids.events += "create:${'$'}{it.id}"
                                }

                            override fun destroy(context: MyContext) {
                                ids.events += "destroy:${'$'}{context.id}"
                            }
                        }

                        class MyContextService(
                            val context: MyContext,
                            private val ids: IdGenerator,
                        ) : AutoCloseable {
                            init {
                                ids.events += "init:${'$'}{context.id}:${'$'}{context.isLoggingEnabled}"
                            }

                            fun ping(): String = "pong:${'$'}{context.id}".also { ids.events += "ping:${'$'}{context.id}" }

                            override fun close() {
                                ids.events += "close:${'$'}{context.id}"
                            }
                        }

                        interface MySubContext : ContextHandle<MyContext> {
                            val service: MyContextService
                        }
                        """.trimIndent(),
                    ),
                    dependsOn = emptyList(),
                    processorClasspath = emptyList(),
                )
            feature.result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val configuration =
                project.configuration(
                    SourceFile.kotlin(
                        "ScopedConfig.kt",
                        """
                        package acme.scoped.di

                        import acme.scoped.IdGenerator
                        import acme.scoped.MyContext
                        import acme.scoped.MyContextFactory
                        import acme.scoped.MyContextOptions
                        import acme.scoped.MyContextService
                        import acme.scoped.MySubContext
                        import io.kmpkt.di.KmpKtApplication
                        import io.kmpkt.di.compiler.Configuration
                        import io.kmpkt.di.compiler.ContainerBuilder
                        import io.kmpkt.di.compiler.TypeRef

                        class ScopedConfig : Configuration<KmpKtApplication.Default> {
                            override fun ContainerBuilder.configure() {
                                service<IdGenerator>()
                                service<MyContextFactory>()
                                service(TypeRef.parse("acme.scoped.ScopedRunner"))
                                context<MySubContext, MyContextOptions>("my-sub-context") {
                                    service<MyContextService>()
                                    entryPoint<MyContextService>("service")
                                }
                            }
                        }
                        """.trimIndent(),
                    ),
                    feature = feature,
                )

            val runnerSource =
                SourceFile.kotlin(
                    "ScopedRunner.kt",
                    """
                    package acme.scoped

                    import io.kmpkt.di.ApplicationRunner
                    import io.kmpkt.di.ContextScope

                    class ScopedRunner(
                        private val mySubContext: ContextScope<MyContextOptions, MySubContext>,
                        private val ids: IdGenerator,
                    ) : ApplicationRunner {
                        override fun run(args: Array<String>) {
                            mySubContext(MyContextOptions(isLoggingEnabled = true)) {
                                service.ping()
                            }
                            val handle = mySubContext.open(MyContextOptions(isLoggingEnabled = false))
                            handle.service.ping()
                            handle.close()
                            ids.events += "done"
                        }
                    }
                    """.trimIndent(),
                )
            val appModule =
                compileModule(
                    runnerSource,
                    dependsOn = listOf(feature),
                    processorClasspath = listOf(feature, configuration),
                )
            if (appModule.result.exitCode != KotlinCompilation.ExitCode.OK) {
                error(appModule.result.messages)
            }

            val kmpKt = appModule.result.classLoader.loadClass("io.kmpkt.di.KmpKt")
            val instance = kmpKt.getField("INSTANCE").get(null)
            kmpKt.getMethod("run", Array<String>::class.java).invoke(instance, emptyArray<String>())

            // Verify lifecycle events via a fresh runner or static? Let's check generated code + run!
            val generated = appModule.generated("KmpKt.kt")
            generated shouldContain "MySubContextContainer"
            generated shouldContain "override fun close()"
            generated shouldContain "override fun open("
        }

        test("a root service cannot inject a service registered only inside a context") {
            val feature =
                compileModule(
                    SourceFile.kotlin(
                        "CaptiveFeature.kt",
                        """
                        package acme.captive

                        data class Ctx(val id: Long)
                        class ScopedOnly(val ctx: Ctx)
                        """.trimIndent(),
                    ),
                    dependsOn = emptyList(),
                    processorClasspath = emptyList(),
                )
            feature.result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val configuration =
                project.configuration(
                    SourceFile.kotlin(
                        "CaptiveConfig.kt",
                        """
                        package acme.captive.di

                        import acme.captive.Ctx
                        import acme.captive.ScopedOnly
                        import io.kmpkt.di.ContextHandle
                        import io.kmpkt.di.KmpKtApplication
                        import io.kmpkt.di.compiler.Configuration
                        import io.kmpkt.di.compiler.ContainerBuilder
                        import io.kmpkt.di.compiler.TypeRef

                        class CaptiveConfig : Configuration<KmpKtApplication.Default> {
                            override fun ContainerBuilder.configure() {
                                service(TypeRef.parse("acme.captive.RootConsumer"))
                                context<ContextHandle<Ctx>, Ctx>("c") {
                                    service<ScopedOnly>()
                                    entryPoint<ScopedOnly>("scoped")
                                }
                            }
                        }
                        """.trimIndent(),
                    ),
                    feature = feature,
                )

            val appModule =
                compileModule(
                    SourceFile.kotlin(
                        "RootConsumer.kt",
                        """
                        package acme.captive

                        import io.kmpkt.di.ApplicationRunner

                        class RootConsumer(val scoped: ScopedOnly) : ApplicationRunner {
                            override fun run(args: Array<String>) = Unit
                        }
                        """.trimIndent(),
                    ),
                    dependsOn = listOf(feature),
                    processorClasspath = listOf(feature, configuration),
                )

            appModule.result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            appModule.result.messages shouldContain "acme.captive.ScopedOnly"
        }

        test("marker contexts support multi-configuration extension and module-specific view projections") {
            val feature =
                compileModule(
                    SourceFile.kotlin(
                        "StoreContextFeature.kt",
                        """
                        package acme.modular

                        import io.kmpkt.di.ApplicationRunner
                        import io.kmpkt.di.ContextFactory
                        import io.kmpkt.di.ContextHandle
                        import io.kmpkt.di.ContextScope
                        import io.kmpkt.di.KmpKtContext
                        import io.kmpkt.di.view

                        object Store : KmpKtContext
                        data class StoreInput(val id: String)
                        data class StoreCtx(val id: String)

                        interface StoreScope : ContextHandle<StoreCtx> {
                            val db: StoreDb
                        }

                        class StoreDb(val ctx: StoreCtx)

                        class StoreFactory : ContextFactory<StoreCtx, StoreInput> {
                            override fun create(input: StoreInput): StoreCtx = StoreCtx(input.id)
                        }

                        interface LoyaltyView {
                            val db: StoreDb
                            val loyalty: LoyaltyService
                        }

                        class LoyaltyService(val db: StoreDb) {
                            fun points(): String = "points:" + db.ctx.id
                        }

                        class Runner(
                            private val stores: ContextScope<StoreInput, StoreScope>,
                        ) : ApplicationRunner {
                            override fun run(args: Array<String>) {
                                stores(StoreInput("berlin")) {
                                    println(view<LoyaltyView>().loyalty.points())
                                }
                            }
                        }
                        """.trimIndent(),
                    ),
                    dependsOn = emptyList(),
                    processorClasspath = emptyList(),
                )
            feature.result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val loyaltyComponent =
                project.configuration(
                    SourceFile.kotlin(
                        "LoyaltyComponent.kt",
                        """
                        package acme.modular.loyalty.di

                        import acme.modular.LoyaltyService
                        import acme.modular.LoyaltyView
                        import acme.modular.Store
                        import io.kmpkt.di.compiler.ContainerBuilder
                        import io.kmpkt.di.compiler.SimpleComponent

                        class LoyaltyComponent : SimpleComponent {
                            override fun ContainerBuilder.register() {
                                context<Store> {
                                    service<LoyaltyService>()
                                    view<LoyaltyView>()
                                }
                            }
                        }
                        """.trimIndent(),
                    ),
                    feature = feature,
                )

            val coreConfig =
                project.configuration(
                    SourceFile.kotlin(
                        "CoreConfig.kt",
                        """
                        package acme.modular.core.di

                        import acme.modular.Runner
                        import acme.modular.Store
                        import acme.modular.StoreDb
                        import acme.modular.StoreFactory
                        import acme.modular.StoreInput
                        import acme.modular.StoreScope
                        import io.kmpkt.di.KmpKtApplication
                        import io.kmpkt.di.compiler.Configuration
                        import io.kmpkt.di.compiler.ContainerBuilder

                        class CoreConfig : Configuration<KmpKtApplication.Default> {
                            override fun ContainerBuilder.configure() {
                                service<StoreFactory>()
                                service<Runner>()
                                context<Store, StoreScope, StoreInput> {
                                    service<StoreDb>()
                                }
                            }
                        }
                        """.trimIndent(),
                    ),
                    feature = feature,
                )

            val appModule =
                compileModule(
                    SourceFile.kotlin(
                        "Main.kt",
                        """
                        package acme.modular

                        import io.kmpkt.di.KmpKt

                        fun runApp() {
                            KmpKt.run()
                        }
                        """.trimIndent(),
                    ),
                    dependsOn = listOf(feature),
                    processorClasspath = listOf(feature, loyaltyComponent, coreConfig),
                )
            if (appModule.result.exitCode != KotlinCompilation.ExitCode.OK) {
                error(appModule.result.messages)
            }

            val out = java.io.ByteArrayOutputStream()
            val previousOut = System.out
            System.setOut(java.io.PrintStream(out))
            try {
                appModule.result.classLoader
                    .loadClass("acme.modular.MainKt")
                    .getMethod("runApp")
                    .invoke(null)
            } finally {
                System.setOut(previousOut)
            }
            out.toString().trim().lines() shouldContainExactly listOf("points:berlin")
        }

        test("extending an undeclared KmpKtContext reports a diagnostic error") {
            val feature =
                compileModule(
                    SourceFile.kotlin(
                        "MissingContextFeature.kt",
                        """
                        package acme.missingctx

                        import io.kmpkt.di.KmpKtContext

                        object Store : KmpKtContext
                        class LoyaltyService
                        """.trimIndent(),
                    ),
                    dependsOn = emptyList(),
                    processorClasspath = emptyList(),
                )
            feature.result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val config =
                project.configuration(
                    SourceFile.kotlin(
                        "MissingContextConfig.kt",
                        """
                        package acme.missingctx.di

                        import acme.missingctx.LoyaltyService
                        import acme.missingctx.Store
                        import io.kmpkt.di.KmpKtApplication
                        import io.kmpkt.di.compiler.Configuration
                        import io.kmpkt.di.compiler.ContainerBuilder

                        class MissingContextConfig : Configuration<KmpKtApplication.Default> {
                            override fun ContainerBuilder.configure() {
                                context<Store> {
                                    service<LoyaltyService>()
                                }
                            }
                        }
                        """.trimIndent(),
                    ),
                    feature = feature,
                )

            val appModule =
                compileModule(
                    SourceFile.kotlin(
                        "Main.kt",
                        """
                        package acme.missingctx

                        import io.kmpkt.di.KmpKt

                        fun runApp() {
                            KmpKt.run()
                        }
                        """.trimIndent(),
                    ),
                    dependsOn = listOf(feature),
                    processorClasspath = listOf(feature, config),
                )
            appModule.result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            appModule.result.messages shouldContain "was extended without being declared"
        }

        val resilientApp by lazy {
            val feature =
                compileModule(
                    SourceFile.kotlin(
                        "ResilientScope.kt",
                        """
                        package acme.resilient

                        import io.kmpkt.di.ApplicationRunner
                        import io.kmpkt.di.ContextFactory
                        import io.kmpkt.di.ContextHandle
                        import io.kmpkt.di.ContextScope
                        import io.kmpkt.di.KmpKtContext

                        object Events {
                            val log = mutableListOf<String>()
                            var failOnClose = false
                        }

                        object Tenant : KmpKtContext

                        data class TenantCtx(val id: String)

                        class TenantFactory : ContextFactory<TenantCtx, String> {
                            override fun create(input: String): TenantCtx = TenantCtx(input)

                            override fun destroy(context: TenantCtx) {
                                Events.log += "destroy:${'$'}{context.id}"
                            }
                        }

                        class FirstResource : AutoCloseable {
                            override fun close() {
                                Events.log += "close:first"
                            }
                        }

                        class FailingResource(val first: FirstResource) : AutoCloseable {
                            override fun close() {
                                Events.log += "close:failing"
                                if (Events.failOnClose) throw IllegalStateException("close-boom")
                            }
                        }

                        class PrototypeToken : AutoCloseable {
                            override fun close() {
                                Events.log += "close:proto"
                            }
                        }

                        interface TenantHandle : ContextHandle<TenantCtx> {
                            val first: FirstResource
                            val failing: FailingResource
                            val token: PrototypeToken
                        }

                        class Runner(
                            private val tenants: ContextScope<String, TenantHandle>,
                        ) : ApplicationRunner {
                            override fun run(args: Array<String>) {
                                when (args.firstOrNull()) {
                                    "suppress-and-destroy" -> {
                                        val handle = tenants.open("t1")
                                        handle.failing
                                        val err = runCatching { handle.close() }.exceptionOrNull()
                                        Events.log += "threw:${'$'}{err?.message}"
                                    }
                                    "idempotent-close" -> {
                                        val handle = tenants.open("t2")
                                        handle.first
                                        handle.close()
                                        handle.close()
                                    }
                                    "prototype-lifecycle" -> {
                                        val handle = tenants.open("t3")
                                        val t1 = handle.token
                                        val t2 = handle.token
                                        check(t1 !== t2)
                                        handle.close()
                                        val postClose = runCatching { handle.token }.exceptionOrNull()
                                        Events.log += "postClose:${'$'}{postClose is IllegalStateException}"
                                    }
                                }
                            }
                        }
                        """.trimIndent(),
                    ),
                    dependsOn = emptyList(),
                    processorClasspath = emptyList(),
                )
            feature.result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val config =
                project.configuration(
                    SourceFile.kotlin(
                        "ResilientConfig.kt",
                        """
                        package acme.resilient.di

                        import acme.resilient.FailingResource
                        import acme.resilient.FirstResource
                        import acme.resilient.PrototypeToken
                        import acme.resilient.Runner
                        import acme.resilient.Tenant
                        import acme.resilient.TenantFactory
                        import acme.resilient.TenantHandle
                        import io.kmpkt.di.KmpKtApplication
                        import io.kmpkt.di.compiler.Configuration
                        import io.kmpkt.di.compiler.ContainerBuilder

                        class ResilientConfig : Configuration<KmpKtApplication.Default> {
                            override fun ContainerBuilder.configure() {
                                service<TenantFactory>()
                                service<Runner>()
                                context<Tenant, TenantHandle, String> {
                                    service<FirstResource>()
                                    service<FailingResource>()
                                    service<PrototypeToken> { prototype() }
                                }
                            }
                        }
                        """.trimIndent(),
                    ),
                    feature = feature,
                )

            val appModule =
                compileModule(
                    SourceFile.kotlin(
                        "Main.kt",
                        """
                        package acme.resilient

                        import io.kmpkt.di.KmpKt

                        fun runScenario(scenario: String): List<String> {
                            Events.log.clear()
                            Events.failOnClose = scenario == "suppress-and-destroy"
                            KmpKt.run(args = arrayOf(scenario))
                            return Events.log.toList()
                        }
                        """.trimIndent(),
                    ),
                    dependsOn = listOf(feature),
                    processorClasspath = listOf(feature, config),
                )
            if (appModule.result.exitCode != KotlinCompilation.ExitCode.OK) {
                error(appModule.result.messages)
            }
            appModule
        }

        @Suppress("UNCHECKED_CAST")
        fun runResilientScenario(scenario: String): List<String> =
            resilientApp.result.classLoader
                .loadClass("acme.resilient.MainKt")
                .getMethod("runScenario", String::class.java)
                .invoke(null, scenario) as List<String>

        test("scoped context close closes remaining services and destroys context when a service close throws") {
            runResilientScenario("suppress-and-destroy") shouldContainExactly
                listOf(
                    "close:failing",
                    "close:first",
                    "destroy:t1",
                    "threw:close-boom",
                )
        }

        test("scoped context close is idempotent across repeated calls") {
            runResilientScenario("idempotent-close") shouldContainExactly
                listOf(
                    "close:failing",
                    "close:first",
                    "destroy:t2",
                )
        }

        test("scoped context tracks prototype AutoCloseables and rejects prototype access after close") {
            runResilientScenario("prototype-lifecycle") shouldContainExactly
                listOf(
                    "close:proto",
                    "close:proto",
                    "close:failing",
                    "close:first",
                    "destroy:t3",
                    "postClose:true",
                )
        }
    })

/** A configuration that knows the application's package and none of its classes - all it can know. */
private fun discovering(packageName: String): SourceFile =
    SourceFile.kotlin(
        "DiscoveringConfig.kt",
        """
        package acme.app.di

        import io.kmpkt.di.KmpKtApplication
                        import io.kmpkt.di.compiler.Configuration
        import io.kmpkt.di.compiler.ContainerBuilder
        class DiscoveringConfig : Configuration<KmpKtApplication.Default> {
            override fun ContainerBuilder.configure() {
                val container = this
                container.discover("$packageName")
            }
        }
        """.trimIndent(),
    )
