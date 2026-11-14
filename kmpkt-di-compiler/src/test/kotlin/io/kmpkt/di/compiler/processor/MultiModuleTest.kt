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
