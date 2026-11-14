package io.kmpkt.di.compiler.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File
import java.io.OutputStream
import java.net.URLClassLoader

/**
 * One Gradle module, compiled on its own: what it put out, and what compiling it said.
 *
 * [output] is what a dependent module gets on its classpath - the classes and, for a `-di` module, the
 * `META-INF/services` files the processor generated.
 */
@OptIn(ExperimentalCompilerApi::class)
internal class Module(
    val result: JvmCompilationResult,
    val output: List<File>,
) {
    fun generated(name: String): String =
        result.outputDirectory.parentFile
            .walkTopDown()
            .single { it.name == name }
            .readText()
}

/**
 * Compiles [sources] against [dependsOn], as `implementation(project(…))` would.
 *
 * [processorClasspath] stands in for `ksp(project(…))`: with it, the processor runs and loads its bundles
 * from exactly those modules, through a class loader of their own as KSP gives a processor one. `null` is a
 * module without the processor.
 */
@OptIn(ExperimentalCompilerApi::class)
internal fun compileModule(
    vararg sources: SourceFile,
    dependsOn: List<Module> = emptyList(),
    processorClasspath: List<Module>? = null,
): Module {
    val compilation =
        KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            jvmTarget = "21"
            classpaths = dependsOn.flatMap { it.output }
            messageOutputStream = OutputStream.nullOutputStream()
            if (processorClasspath != null) {
                val urls = processorClasspath.flatMap { it.output }.map { it.toURI().toURL() }
                val loader = URLClassLoader(urls.toTypedArray(), ContainerProcessorProvider::class.java.classLoader)
                configureKsp {
                    symbolProcessorProviders += ContainerProcessorProvider(loader)
                }
            }
        }
    val result = compilation.compile()

    return Module(result, listOf(result.outputDirectory, compilation.kspSourcesDir.resolve("resources")).filter { it.exists() })
}
