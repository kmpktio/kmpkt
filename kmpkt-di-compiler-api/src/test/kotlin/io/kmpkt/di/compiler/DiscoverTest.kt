package io.kmpkt.di.compiler

import io.kmpkt.di.KmpKtApplication
import io.kmpkt.di.compiler.fixture.FakeTypeOracle
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class AnchorHandler : Runnable {
    override fun run() = Unit
}

class NotAHandler

private fun type(name: String) = TypeRef("acme.app.$name")

private fun id(name: String) = ServiceId("acme.app.$name")

private fun key(name: String) = ServiceKey(type(name))

private val port = type("ListSource")
private val text = TypeRef("kotlin.String")

/** An application's sources as `discover` would find them: a port, two adapters, a handler, and what is no service. */
private class Application {
    val types =
        FakeTypeOracle().apply {
            declare(port)
            declareTraits(port, ClassTrait.Interface)
            declare(type("KeepListSource"), supertypes = listOf(port, TypeRef("java.io.Closeable")))
            declare(type("SortListHandler"), parameters = listOf(Parameter("listSource", port)))
            declare(type("Unwireable"), parameters = listOf(Parameter("name", text)))
            declare(type("Item"), parameters = listOf(Parameter("name", text)))
            declareTraits(type("Item"), ClassTrait.Data)
            ClassTrait.entries.forEach { trait ->
                declare(type("Excluded$trait"))
                declareTraits(type("Excluded$trait"), trait)
            }
        }
    val diagnostics = CollectingDiagnostics()
    val container = ContainerBuilder(types, diagnostics)

    fun compile(
        vararg entryPoints: ServiceKey,
        components: List<Component<*>> = emptyList(),
        configure: (ContainerBuilder) -> Unit,
    ): CompiledContainer? {
        entryPoints.forEachIndexed { index, entryPoint -> container.entryPoint("entry$index", entryPoint) }

        return ContainerCompiler(components, listOf(Configuration<KmpKtApplication.Default> { configure(this) })).compile(container)
    }
}

class DiscoverTest :
    FunSpec({
        test("registers the plain classes of the package and the packages below it, and nothing that is no service") {
            // Arrange
            val application = Application()

            // Act
            application.container.discover("acme.app")

            // Assert
            application.container.definitions.keys shouldContainExactly
                setOf(id("KeepListSource"), id("SortListHandler"), id("Unwireable"))
            application.container.definitions.getValue(id("KeepListSource")) shouldBe
                ServiceDefinition(
                    id = id("KeepListSource"),
                    type = type("KeepListSource"),
                    source = "discover(\"acme.app\")",
                    discovered = true,
                )
        }

        test("it is that package and no other: not the ones below it, not a sibling whose name starts the same") {
            // Arrange
            val application = Application()
            application.types.declare(type("sub.Below"))
            application.types.declare(TypeRef("acme.apple.Sibling"))

            // Act
            application.container.discover("acme.app")

            // Assert
            application.container.definitions.keys shouldContainExactly
                setOf(id("KeepListSource"), id("SortListHandler"), id("Unwireable"))
        }

        test("excluded classes are left out") {
            // Arrange
            val application = Application()

            // Act
            application.container.discover("acme.app") {
                exclude("acme.app.Unwireable")
                exclude<String>()
            }

            // Assert
            application.container.definitions.keys shouldContainExactly setOf(id("KeepListSource"), id("SortListHandler"))
        }

        test("asked for one kind of class, it takes those and holds them to it: no patience for what cannot be wired") {
            // Arrange
            val application = Application()
            val handler = type("Handler")
            application.types.declare(handler)
            application.types.declareTraits(handler, ClassTrait.Interface)
            application.types.declare(type("GoodHandler"), supertypes = listOf(handler))
            application.types.declare(type("BrokenHandler"), parameters = listOf(Parameter("name", text)), supertypes = listOf(handler))

            // Act
            val compiled =
                application.compile(key("GoodHandler")) {
                    it.discover("acme.app") {
                        services(handler)
                    }
                }

            // Assert
            application.container.definitions.keys shouldContainExactly setOf(id("GoodHandler"), id("BrokenHandler"))
            application.container.definitions.getValue(id("GoodHandler")) shouldBe
                ServiceDefinition(
                    id = id("GoodHandler"),
                    type = type("GoodHandler"),
                    source = "discover(\"acme.app\") { services<acme.app.Handler>() }",
                )
            compiled.shouldBeNull()
            application.diagnostics.errors.single() shouldBe
                "Cannot autowire parameter \"name\" of \"acme.app.BrokenHandler\" (registered by discover(\"acme.app\") " +
                "{ services<acme.app.Handler>() }): no service is bound to kotlin.String."
        }

        test("discoverPackageOf names the package by a class in it and the kind by a type") {
            // Arrange
            val application = Application()
            application.types.declare(typeRef<AnchorHandler>(), supertypes = listOf(typeRef<Runnable>()))
            application.types.declare(typeRef<NotAHandler>())

            // Act
            application.container.discover("io.kmpkt.di.compiler") {
                services<Runnable>()
            }

            // Assert
            application.container.definitions.keys shouldContainExactly setOf(ServiceId.of<AnchorHandler>())
        }

        test("a class registered by name keeps its registration, even one discovery would have skipped") {
            // Arrange
            val application = Application()
            application.container.service(type("SortListHandler")) { prototype() }
            application.container.service(type("Item"))

            // Act
            application.container.discover("acme.app")

            // Assert
            application.container.definitions
                .getValue(id("SortListHandler"))
                .scope shouldBe Scope.Prototype
            application.container.definitions
                .getValue(id("SortListHandler"))
                .discovered shouldBe false
            application.container.has(id("Item")) shouldBe true
        }

        test("a discovered service is bound to its own type and its interfaces, but not to the platform's") {
            // Arrange
            val application = Application()

            // Act
            application.container.discover("acme.app")

            // Assert
            application.container.find(ServiceKey(port))?.id shouldBe id("KeepListSource")
            application.container.find(key("KeepListSource"))?.id shouldBe id("KeepListSource")
            application.container.find(ServiceKey(TypeRef("java.io.Closeable"))).shouldBeNull()
        }

        test("two discovered implementations bind the interface to neither, and asking for it names both") {
            // Arrange
            val application = Application()
            application.types.declare(type("FakeListSource"), supertypes = listOf(port))

            // Act
            val compiled =
                application.compile(key("SortListHandler")) { it.discover("acme.app") }

            // Assert
            compiled.shouldBeNull()
            application.container.find(ServiceKey(port)).shouldBeNull()
            application.diagnostics.errors shouldContainExactly
                listOf(
                    "Cannot autowire parameter \"listSource\" of \"acme.app.SortListHandler\" (registered by " +
                        "discover(\"acme.app\")): acme.app.ListSource is implemented by 2 discovered services " +
                        "(acme.app.KeepListSource, acme.app.FakeListSource) and bound to none of them. " +
                        "Bind the one that is meant.",
                )
        }

        test("an ambiguous interface is no problem while nobody asks for it, and says so when an entry point does") {
            // Arrange
            val unasked = Application().apply { types.declare(type("FakeListSource"), supertypes = listOf(port)) }
            val asked = Application().apply { types.declare(type("FakeListSource"), supertypes = listOf(port)) }

            // Act
            val fine = unasked.compile(key("KeepListSource")) { it.discover("acme.app") }
            asked.compile(ServiceKey(port)) { it.discover("acme.app") }

            // Assert
            fine?.definitions?.map { it.id } shouldBe listOf(id("KeepListSource"))
            asked.diagnostics.errors shouldContainExactly
                listOf(
                    "Entry point \"entry0\" asks for acme.app.ListSource, but acme.app.ListSource is implemented by " +
                        "2 discovered services (acme.app.KeepListSource, acme.app.FakeListSource) and bound to " +
                        "none of them. Bind the one that is meant.",
                )
        }

        test("binding by name settles an ambiguity, whether it comes before or after discovering") {
            // Arrange
            val before = Application().apply { types.declare(type("FakeListSource"), supertypes = listOf(port)) }
            val after = Application().apply { types.declare(type("FakeListSource"), supertypes = listOf(port)) }

            // Act
            before.container.alias(ServiceKey(port), id("FakeListSource"))
            before.container.discover("acme.app")
            after.container.discover("acme.app")
            after.container.alias(ServiceKey(port), id("FakeListSource"))

            // Assert
            before.container.find(ServiceKey(port))?.id shouldBe id("FakeListSource")
            after.container.find(ServiceKey(port))?.id shouldBe id("FakeListSource")
            after.container.candidatesFor(ServiceKey(port)).shouldBeEmpty()
        }

        test("removing one of two candidates settles an ambiguity as well; three less one is still ambiguous") {
            // Arrange
            val application = Application()
            application.types.declare(type("FakeListSource"), supertypes = listOf(port))
            application.types.declare(type("ThirdListSource"), supertypes = listOf(port))
            application.container.discover("acme.app")

            // Act + Assert
            application.container.remove(id("ThirdListSource"))
            application.container.candidatesFor(ServiceKey(port)) shouldContainExactly
                listOf(id("KeepListSource"), id("FakeListSource"))

            application.container.remove(id("FakeListSource"))
            application.container.find(ServiceKey(port))?.id shouldBe id("KeepListSource")
        }

        test("a discovered class that cannot be wired is dropped quietly while nothing needs it") {
            // Arrange
            val application = Application()

            // Act
            val compiled = application.compile(key("SortListHandler")) { it.discover("acme.app") }

            // Assert
            application.diagnostics.errors.shouldBeEmpty()
            compiled?.definitions?.map { it.id } shouldBe listOf(id("KeepListSource"), id("SortListHandler"))
        }

        test("the same class fails the compilation with its autowiring error once an entry point reaches it") {
            // Arrange
            val application = Application()
            application.types.declare(type("NeedsIt"), parameters = listOf(Parameter("unwireable", type("Unwireable"))))

            // Act
            val compiled = application.compile(key("NeedsIt")) { it.discover("acme.app") }

            // Assert
            compiled.shouldBeNull()
            application.diagnostics.errors shouldContainExactly
                listOf(
                    "Cannot autowire parameter \"name\" of \"acme.app.Unwireable\" (registered by discover(\"acme.app\")): " +
                        "no service is bound to kotlin.String.",
                )
        }

        test("a class registered by name gets no such patience") {
            // Arrange
            val application = Application()

            // Act
            val compiled =
                application.compile(key("SortListHandler")) {
                    it.discover("acme.app")
                    it.service(type("Item"))
                }

            // Assert
            compiled.shouldBeNull()
            application.diagnostics.errors.single() shouldBe
                "Cannot autowire parameter \"name\" of \"acme.app.Item\": no service is bound to kotlin.String."
        }

        test("what a component binds by name wins over what discovery inferred") {
            // Arrange
            val application = Application()
            application.types.declare(type("bundle.BundledListSource"), supertypes = listOf(port))
            val component =
                object : Component<Unit> {
                    override fun defaults() = Unit

                    override fun ContainerBuilder.register(config: Unit) {
                        service(type("bundle.BundledListSource")) { bind(port) }
                    }
                }

            // Act
            val compiled =
                application.compile(ServiceKey(port), components = listOf(component)) { it.discover("acme.app") }

            // Assert
            compiled?.entryPoints?.single()?.id shouldBe id("bundle.BundledListSource")
        }

        test("a component may discover too, and what it infers stays an inference when it is merged") {
            // Arrange
            val application = Application()
            application.types.declare(type("FakeListSource"), supertypes = listOf(port))
            val component =
                object : Component<Unit> {
                    override fun defaults() = Unit

                    override fun ContainerBuilder.register(config: Unit) {
                        discover("acme.app")
                    }
                }

            // Act
            val compiled = application.compile(ServiceKey(port), components = listOf(component)) {}

            // Assert
            compiled.shouldBeNull()
            application.diagnostics.errors.single() shouldBe
                "Entry point \"entry0\" asks for acme.app.ListSource, but acme.app.ListSource is implemented by " +
                "2 discovered services (acme.app.KeepListSource, acme.app.FakeListSource) and bound to " +
                "none of them. Bind the one that is meant."
        }
    })

class TypeRefParseTest :
    FunSpec({
        test("reads back what toString writes") {
            listOf(
                typeRef<String>(),
                typeRef<String?>(),
                typeRef<Map<String, List<Comparable<*>?>>>(),
                typeRef<List<Map<String, Int>>?>(),
            ).forEach { TypeRef.parse(it.toString()) shouldBe it }
        }

        test("does not mind spaces") {
            TypeRef.parse(" kotlin.collections.Map< kotlin.String , kotlin.Int > ") shouldBe typeRef<Map<String, Int>>()
        }

        test("says where a malformed type goes wrong") {
            shouldThrow<IllegalArgumentException> { TypeRef.parse("") }.message shouldBe
                "Expected a type name at position 0 of \"\""
            shouldThrow<IllegalArgumentException> { TypeRef.parse("a.B<a.C") }.message shouldBe
                "Expected \">\" at position 7 of \"a.B<a.C\""
            shouldThrow<IllegalArgumentException> { TypeRef.parse("a.B>") }.message shouldBe
                "Unexpected \">\" at the end of \"a.B>\""
            shouldThrow<IllegalArgumentException> { TypeRef.parse("a.B<>") }.message shouldBe
                "Expected a type name at position 4 of \"a.B<>\""
            shouldThrow<IllegalArgumentException> { TypeRef.parse("*") }
        }
    })
