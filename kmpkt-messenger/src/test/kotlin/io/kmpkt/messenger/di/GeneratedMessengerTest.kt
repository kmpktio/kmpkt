package io.kmpkt.messenger.di

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import io.kmpkt.di.compiler.processor.ContainerProcessorProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File
import java.io.OutputStream
import java.net.URLClassLoader

private val handlers =
    SourceFile.kotlin(
        "Handlers.kt",
        """
        package acme

        import io.kmpkt.messenger.message.Message
        import io.kmpkt.messenger.message.MessageHandler
        import io.kmpkt.messenger.retry.RecoverableException

        class Ping : Message
        class Happened : Message

        class Lost : RuntimeException("lost the race"), RecoverableException

        class PingHandler : MessageHandler<Ping, String> {
            var attempts = 0

            // Fails the first time, the way a command does that lost an optimistic lock.
            override suspend fun invoke(message: Ping): String {
                attempts += 1
                if (attempts == 1) throw Lost()

                return "pong after ${'$'}attempts attempts"
            }
        }

        class FirstListener : MessageHandler<Happened, String> {
            override suspend fun invoke(message: Happened) = "first"
        }

        class SecondListener : MessageHandler<Happened, String> {
            override suspend fun invoke(message: Happened) = "second"
        }
        """.trimIndent(),
    )

private val configuration =
    SourceFile.kotlin(
        "AcmeConfiguration.kt",
        """
        package acme.di

        import acme.FirstListener
        import acme.Happened
        import acme.Ping
        import acme.PingHandler
        import acme.SecondListener
        import io.kmpkt.di.ApplicationRunner
        import io.kmpkt.di.KmpKtApplication
        import io.kmpkt.di.compiler.Argument
        import io.kmpkt.di.compiler.Configuration
        import io.kmpkt.di.compiler.ContainerBuilder
        import io.kmpkt.di.compiler.ServiceKey
        import io.kmpkt.di.compiler.ServiceReference
        import io.kmpkt.di.compiler.typeRef
        import io.kmpkt.messenger.bus.MessageBus
        import io.kmpkt.messenger.di.MessageHandlerTag
        import io.kmpkt.messenger.di.messenger
        import io.kmpkt.messenger.di.delay
        import io.kmpkt.messenger.di.maxRetries
        import io.kmpkt.messenger.handler.HandlerPolicy
        import io.kmpkt.messenger.handler.stamp.HandledStamp
        import io.kmpkt.messenger.retry.MultiplierRetryStrategy
        import io.kmpkt.messenger.stamp.ErrorDetailsStamp
        import io.kmpkt.messenger.transport.InMemoryTransport
        import io.kmpkt.messenger.transport.SyncTransport
        import io.kmpkt.messenger.transport.registry.TransportRegistry
        import kotlinx.coroutines.runBlocking
        import kotlin.time.Duration.Companion.seconds

        object Output {
            var result: List<Any?> = emptyList()
        }

        class MessengerRunner(
            private val commands: MessageBus,
            private val events: MessageBus,
            private val transports: TransportRegistry,
        ) : ApplicationRunner {
            override fun run(args: Array<String>) = runBlocking {
                val failed = transports.get("failed") as InMemoryTransport
                Output.result =
                    listOf(
                        commands.dispatch(Ping()).all<HandledStamp>().map { it.result },
                        events.dispatch(Happened()).all<HandledStamp>().map { it.result },
                        // No handler for it on the command bus: given up on at once, and put aside rather than thrown.
                        runCatching { commands.dispatch(Happened()) }.exceptionOrNull()?.let { it::class.simpleName },
                        events.dispatch(Ping()).all<HandledStamp>().size,
                        failed.pending().map { it.last<ErrorDetailsStamp>()?.exceptionClass },
                    )
            }
        }

        class AcmeConfiguration : Configuration<KmpKtApplication.Default> {
            override fun ContainerBuilder.configure() {
                val container = this
                container.messenger {
                    val failed = transport<InMemoryTransport>("failed")
                    val sync =
                        transport<SyncTransport>("sync") {
                            retry<MultiplierRetryStrategy> {
                                maxRetries = 1
                                delay = 2.seconds
                            }
                            failureTransport = failed
                        }

                    bus("command") {
                        defaultTransport = sync
                    }
                    bus("event") {
                        policy = HandlerPolicy.MULTIPLE
                    }
                }

                container.service<PingHandler> {
                    tag(MessageHandlerTag(bus = "command"))
                }
                container.service<FirstListener> {
                    tag(MessageHandlerTag(bus = "event"))
                }
                container.service<SecondListener> {
                    tag(MessageHandlerTag(bus = "event", priority = 10))
                }
                container.service<MessengerRunner> {
                    argument("commands", Argument.Service(ServiceReference.ByKey(ServiceKey(typeRef<MessageBus>(), "command"))))
                    argument("events", Argument.Service(ServiceReference.ByKey(ServiceKey(typeRef<MessageBus>(), "event"))))
                }
            }
        }
        """.trimIndent(),
    )

private val application =
    SourceFile.kotlin(
        "App.kt",
        """
        package acme

        import acme.di.Output

        fun run(): List<Any?> {
            io.kmpkt.di.KmpKt.run()
            return Output.result
        }
        """.trimIndent(),
    )

@OptIn(ExperimentalCompilerApi::class)
private fun compile(
    vararg sources: SourceFile,
    classpath: List<File> = emptyList(),
    processorClasspath: List<File> = emptyList(),
): Pair<JvmCompilationResult, List<File>> {
    val compilation =
        KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            jvmTarget = "21"
            classpaths = classpath
            messageOutputStream = OutputStream.nullOutputStream()
            // The bundle itself comes from this module's own META-INF/services, on the test classpath.
            val loader =
                URLClassLoader(processorClasspath.map { it.toURI().toURL() }.toTypedArray(), MessengerComponent::class.java.classLoader)
            configureKsp {
                symbolProcessorProviders += ContainerProcessorProvider(loader)
            }
        }
    val result = compilation.compile()

    return result to listOf(result.outputDirectory, compilation.kspSourcesDir.resolve("resources")).filter { it.exists() }
}

/**
 * The bundle through the real processor and the Kotlin compiler: what [MessengerComponentTest] asserts on the
 * model has to come out as code that compiles against the messenger's own signatures, and runs.
 */
@OptIn(ExperimentalCompilerApi::class)
class GeneratedMessengerTest :
    FunSpec({
        test("two buses with their own handlers compile and dispatch; the command bus retries and puts aside what it gives up on") {
            val (configured, configurationOutput) = compile(handlers, configuration)
            configured.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val (compiled, _) = compile(application, classpath = configurationOutput, processorClasspath = configurationOutput)

            compiled.messages.lines().filter { it.startsWith("e: ") } shouldBe emptyList()
            compiled.classLoader
                .loadClass("acme.AppKt")
                .getMethod("run")
                .invoke(null) shouldBe
                listOf(listOf("pong after 2 attempts"), listOf("second", "first"), null, 0, listOf("NoHandlerForMessageException"))
        }
    })
