package io.kmpkt.di.compiler.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.CompiledContainer
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.Instantiation
import io.kmpkt.di.compiler.ResolvedEntryPoint
import io.kmpkt.di.compiler.Scope
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.TypeRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun typeName(type: TypeRef): TypeName {
    val className = ClassName.bestGuess(type.qualifiedName)
    val arguments = type.arguments.map { typeName((it as TypeArgument.Of).type) }

    return if (arguments.isEmpty()) className else className.parameterizedBy(arguments)
}

private fun generate(compiled: CompiledContainer): String =
    ContainerGenerator(::typeName)
        .generateApplications(mapOf(ClassName("acme", "App") to compiled))
        .toString()
        .substringAfter("    private class ")
        .substringBeforeLast("\n}\n")
        .lines()
        .joinToString("\n") { it.removePrefix("    ") } + "\n"

private fun byId(id: String): Argument = Argument.Service(ServiceReference.ById(ServiceId(id)))

private fun literal(value: ConstValue): Argument = Argument.Literal(value)

class ContainerGeneratorTest :
    FunSpec({
        test("names that would collide are told apart, and the entry point keeps the contract's") {
            // Arrange
            val clock = ServiceDefinition(ServiceId("acme.Clock"), TypeRef("acme.Clock"))
            val other = ServiceDefinition(ServiceId("other.Clock"), TypeRef("other.Clock"))
            val external = ServiceDefinition(ServiceId("acme.Config"), TypeRef("acme.Config"), Instantiation.External("clock"))
            val compiled =
                CompiledContainer(
                    definitions = listOf(clock, other, external),
                    entryPoints =
                        listOf(
                            ResolvedEntryPoint("clock", TypeRef("acme.Clock"), clock.id),
                            ResolvedEntryPoint("config", TypeRef("acme.Config"), external.id),
                        ),
                )

            // Act
            val source = generate(compiled)

            // Assert
            source shouldBe
                """
                AppContainer(
                    clock: Config,
                ) {
                    private val clock___: Config = clock

                    override val clock: AcmeClock
                        get() = clock_

                    override val config: Config
                        get() = clock___

                    private val clock_: AcmeClock by lazy {
                        AcmeClock()
                    }

                    private val clock__: OtherClock by lazy {
                        OtherClock()
                    }

                    init {
                        clock_
                        clock__
                    }

                    public fun run(args: Array<String>) {
                    }
                }

                """.trimIndent()
        }

        test("every kind of argument is written as the Kotlin a person would write") {
            // Arrange
            val shared = ServiceDefinition(ServiceId("acme.Shared"), TypeRef("acme.Shared"))
            val prototype = ServiceDefinition(ServiceId("acme.Job"), TypeRef("acme.Job"), scope = Scope.Prototype)
            val inlined = ServiceDefinition(ServiceId("acme.Inlined"), TypeRef("acme.Inlined"), inlined = true)
            val generic = TypeRef("acme.Store", listOf(TypeArgument.Of(TypeRef("acme.Row"))))
            val consumer =
                ServiceDefinition(
                    id = ServiceId("store"),
                    type = generic,
                    instantiation = Instantiation.ObjectFunction(TypeRef("acme.Stores"), "create"),
                    arguments =
                        mapOf(
                            "shared" to byId("acme.Shared"),
                            "job" to byId("acme.Job"),
                            "inlined" to byId("acme.Inlined"),
                            "lazy" to Argument.Deferred(ServiceReference.ById(shared.id), Argument.Deferred.Kind.Lazy),
                            "provider" to Argument.Deferred(ServiceReference.ById(prototype.id), Argument.Deferred.Kind.Provider),
                            "skipped" to Argument.Default,
                            "none" to Argument.Sequence(emptyList()),
                            "empty" to Argument.Dictionary(emptyList()),
                            "text" to literal(ConstValue.Text("a \"quoted\" \$text")),
                            "flag" to literal(ConstValue.Flag(true)),
                            "int" to literal(ConstValue.Number(3)),
                            "long" to literal(ConstValue.Number(3L)),
                            "float" to literal(ConstValue.Number(1.5f)),
                            "double" to literal(ConstValue.Number(1.5)),
                            "absent" to literal(ConstValue.Null),
                            "entry" to literal(ConstValue.EnumEntry(TypeRef("acme.Mode"), "FAST")),
                            "type" to literal(ConstValue.ClassLiteral(generic)),
                        ),
                )
            val clock =
                ServiceDefinition(
                    id = ServiceId("java.time.Clock"),
                    type = TypeRef("java.time.Clock"),
                    instantiation = Instantiation.StaticFactory(TypeRef("java.time.Clock"), "systemUTC"),
                )
            val compiled =
                CompiledContainer(
                    definitions = listOf(shared, prototype, inlined, consumer, clock),
                    entryPoints =
                        listOf(
                            ResolvedEntryPoint("job", TypeRef("acme.Job"), prototype.id),
                            ResolvedEntryPoint("time", TypeRef("java.time.Clock"), clock.id),
                        ),
                )

            // Act
            val source = generate(compiled)

            // Assert
            source shouldBe
                """
                AppContainer {
                    override val job: Job
                        get() = newJob()

                    override val time: Clock
                        get() = clock

                    private val shared: Shared by lazy {
                        Shared()
                    }

                    private val store: Store<Row> by lazy {
                        Stores.create(
                            shared = shared,
                            job = newJob(),
                            inlined = Inlined(),
                            lazy = lazy { shared },
                            provider = { newJob() },
                            none = emptyList(),
                            empty = emptyMap(),
                            text = "a \"quoted\" ${'$'}{'${'$'}'}text",
                            flag = true,
                            int = 3,
                            long = 3L,
                            float = 1.5f,
                            double = 1.5,
                            absent = null,
                            entry = Mode.FAST,
                            type = Store::class,
                        )
                    }

                    private val clock: Clock by lazy {
                        Clock.systemUTC()
                    }

                    init {
                        shared
                        store
                        clock
                    }

                    private fun newJob(): Job = Job()

                    public fun run(args: Array<String>) {
                    }
                }

                """.trimIndent()
        }

        test("an argument the pipeline should have resolved is a bug, not something to write out") {
            val unresolved =
                ServiceDefinition(ServiceId("a"), TypeRef("acme.A"), arguments = mapOf("inner" to Argument.Inner))
            val compiled = CompiledContainer(listOf(unresolved), listOf(ResolvedEntryPoint("a", TypeRef("acme.A"), unresolved.id)))

            shouldThrow<IllegalStateException> { generate(compiled) }
        }

        test("lazy services are skipped in init, and generateApplication writes KmpKt.run") {
            val eager = ServiceDefinition(ServiceId("acme.Eager"), TypeRef("acme.Eager"))
            val deferred = ServiceDefinition(ServiceId("acme.Deferred"), TypeRef("acme.Deferred"), lazy = true)
            val runner = ServiceDefinition(ServiceId("acme.Runner"), TypeRef("acme.Runner"))
            val compiled = CompiledContainer(listOf(eager, deferred, runner), emptyList(), listOf(runner.id))

            val source =
                ContainerGenerator(::typeName)
                    .generateApplications(mapOf(ClassName("io.kmpkt.di", "KmpKtApplication", "Default") to compiled))
                    .toString()
                    .substringAfter("public object ")

            val q = "\"\"\""
            source shouldBe
                """
                KmpKt {
                    public fun run(application: Class<out KmpKtApplication>, args: Array<String> = emptyArray()) {
                        when (application) {
                            KmpKtApplication.Default::class.java -> KmpKtApplicationDefaultContainer().run(args)
                            else -> error(${q}No Configuration<${'$'}{application.name}> registered.$q)
                        }
                    }

                    @JvmName("runApplication")
                    public inline fun <reified A : KmpKtApplication> run(args: Array<String> = emptyArray()) {
                        run(A::class.java, args)
                    }

                    public fun run(args: Array<String> = emptyArray()) {
                        run(KmpKtApplication.Default::class.java, args)
                    }

                    private class KmpKtApplicationDefaultContainer {
                        private val eager: Eager by lazy {
                            Eager()
                        }

                        private val deferred: Deferred by lazy {
                            Deferred()
                        }

                        private val runner: Runner by lazy {
                            Runner()
                        }

                        init {
                            eager
                            runner
                        }

                        public fun run(args: Array<String>) {
                            runner.run(args)
                        }
                    }
                }

                """.trimIndent()
        }
    })
