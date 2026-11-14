package io.kmpkt.di.gradle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import java.nio.file.Path

class KmpktDiPluginTest :
    FunSpec({
        test("configures di sourceSet, diJar, and diElements on a Kotlin JVM project") {
            // Arrange
            val project = ProjectBuilder.builder().build()
            project.pluginManager.apply("org.jetbrains.kotlin.jvm")

            // Act
            project.pluginManager.apply("io.kmpkt.di")

            // Assert
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            sourceSets.findByName("di").shouldNotBeNull()
            project.tasks.findByName("diJar").shouldNotBeNull()
            project.configurations.findByName("diElements").shouldNotBeNull()
            project.configurations.findByName("diProcessorClasspath").shouldNotBeNull()

            val extension = project.extensions.getByType(KmpktDiExtension::class.java)
            extension.groupId.get() shouldBe "io.kmpkt"
            project.configurations.names shouldContain "kspDi"
        }

        test("resolves transitive diElements and runs KmpKt.run<App>() across modules") {
            val root = Files.createTempDirectory("kmpkt-di-plugin-test")
            try {
                root.write(
                    "settings.gradle.kts",
                    """
                    pluginManagement {
                        repositories {
                            gradlePluginPortal()
                            mavenCentral()
                        }
                    }

                    dependencyResolutionManagement {
                        repositories {
                            mavenCentral()
                            mavenLocal()
                        }
                    }

                    rootProject.name = "test-app"
                    include(":feature", ":core", ":app")
                    """.trimIndent(),
                )

                root.write(
                    "feature/build.gradle.kts",
                    """
                    plugins {
                        kotlin("jvm")
                        id("io.kmpkt.di")
                    }

                    dependencies {
                        implementation("io.kmpkt:kmpkt-di:0.1.0")
                    }
                    """.trimIndent(),
                )
                root.write(
                    "feature/src/main/kotlin/example/feature/Greeter.kt",
                    """
                    package example.feature

                    class Greeter {
                        fun hello(): String = "hello"
                    }
                    """.trimIndent(),
                )
                root.write(
                    "feature/src/di/kotlin/example/feature/di/FeatureComponent.kt",
                    """
                    package example.feature.di

                    import example.feature.Greeter
                    import io.kmpkt.di.compiler.ContainerBuilder
                    import io.kmpkt.di.compiler.SimpleComponent

                    class FeatureComponent : SimpleComponent {
                        override fun ContainerBuilder.register(config: Unit) {
                            service<Greeter>()
                        }
                    }
                    """.trimIndent(),
                )

                root.write(
                    "core/build.gradle.kts",
                    """
                    plugins {
                        kotlin("jvm")
                        id("io.kmpkt.di")
                    }

                    dependencies {
                        api(project(":feature"))
                    }
                    """.trimIndent(),
                )
                root.write(
                    "core/src/main/kotlin/example/core/CoreApp.kt",
                    """
                    package example.core

                    import example.feature.Greeter
                    import io.kmpkt.di.ApplicationRunner
                    import io.kmpkt.di.KmpKtApplication

                    object App : KmpKtApplication

                    class HelloRunner(
                        private val greeter: Greeter,
                    ) : ApplicationRunner {
                        override fun run(args: Array<String>) {
                            println("GREETING=${'$'}{greeter.hello()}")
                        }
                    }
                    """.trimIndent(),
                )
                root.write(
                    "core/src/di/kotlin/example/core/di/CoreConfiguration.kt",
                    """
                    package example.core.di

                    import example.core.App
                    import io.kmpkt.di.compiler.Configuration
                    import io.kmpkt.di.compiler.ContainerBuilder

                    class CoreConfiguration : Configuration<App> {
                        override fun ContainerBuilder.configure() {
                            discover("example.core")
                        }
                    }
                    """.trimIndent(),
                )

                root.write(
                    "app/build.gradle.kts",
                    """
                    plugins {
                        application
                        kotlin("jvm")
                        id("io.kmpkt.di")
                    }

                    dependencies {
                        implementation(project(":core"))
                    }

                    application {
                        mainClass.set("example.app.MainKt")
                    }
                    """.trimIndent(),
                )
                root.write(
                    "app/src/main/kotlin/example/app/Main.kt",
                    """
                    package example.app

                    import example.core.App
                    import io.kmpkt.di.KmpKt

                    fun main(args: Array<String>) = KmpKt.run<App>(args)
                    """.trimIndent(),
                )

                val result =
                    GradleRunner
                        .create()
                        .withProjectDir(root.toFile())
                        .withPluginClasspath()
                        .withArguments(":app:run", "--console=plain")
                        .build()

                result.output shouldContain "GREETING=hello"
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })

private fun Path.write(
    relative: String,
    content: String,
) {
    val target = resolve(relative)
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
}
