package io.kmpkt.di.compiler.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.writeTo
import io.kmpkt.di.compiler.CollectingDiagnostics
import io.kmpkt.di.compiler.CompiledContainer
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ContainerCompiler
import io.kmpkt.di.compiler.Instantiation
import io.kmpkt.di.compiler.generator.ContainerGenerator
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.ServiceLoader

/**
 * The processor's two jobs, each triggered by what the module contains rather than by an option:
 *
 * - in a `-di` module or source set: every `Component` and `Configuration` is listed in `META-INF/services`
 * - in the application module: all `Configuration`s and `Component`s on the processor's classpath are
 *   compiled per `KmpKtApplication` and `io.kmpkt.di.KmpKt` is generated
 */
public class ContainerProcessorProvider(
    private val classLoader: ClassLoader,
) : SymbolProcessorProvider {
    /**
     * The loader this class came from - not the system or context class loader. KSP loads a processor and
     * everything declared with `ksp(…)` through one loader of its own, and that is the only one that can see
     * the components.
     */
    public constructor() : this(ContainerProcessorProvider::class.java.classLoader)

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ContainerProcessor(environment.codeGenerator, environment.logger, classLoader)
}

internal class ContainerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val classLoader: ClassLoader,
) : SymbolProcessor {
    private var servicesWritten = false
    private var isDiModule = false
    private var kmpKtGenerated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (!servicesWritten) {
            servicesWritten = true
            val wroteComponents = writeServices(resolver, Component::class.java)
            val wroteConfigurations = writeServices(resolver, Configuration::class.java)
            isDiModule = wroteComponents || wroteConfigurations
        }

        if (!isDiModule && !kmpKtGenerated) {
            val configurations = ServiceLoader.load(Configuration::class.java, classLoader).toList()
            if (configurations.isNotEmpty()) {
                kmpKtGenerated = true
                application(resolver, configurations)
            }
        }

        return emptyList()
    }

    /**
     * Lists every class of this module that is a [service] in `META-INF/services`. Implementing the
     * interface is the declaration; there is no annotation to forget.
     */
    private fun writeServices(
        resolver: Resolver,
        service: Class<*>,
    ): Boolean {
        val implementations =
            resolver
                .getAllFiles()
                .flatMap { file -> file.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested) }
                .filter { it.classKind == ClassKind.CLASS && !it.isAbstract() }
                .filter { declaration -> declaration.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == service.name } }
                .toList()
        val loadable =
            implementations.filter { declaration ->
                val constructible =
                    declaration.isPublic() &&
                        declaration.primaryConstructor.let {
                            it == null ||
                                (it.isPublic() && it.parameters.none { parameter -> !parameter.hasDefault })
                        }
                if (!constructible) {
                    logger.error(
                        "A ${service.simpleName} is created by the processor, which needs it public with a public constructor that takes no arguments.",
                        declaration,
                    )
                }
                constructible
            }

        val names = loadable.mapNotNull { it.binaryName() }
        if (names.isEmpty()) return false

        codeGenerator
            .createNewFileByPath(
                Dependencies(aggregating = true, *loadable.mapNotNull { it.containingFile }.toTypedArray()),
                "META-INF/services/${service.name}",
                extensionName = "",
            ).bufferedWriter()
            .use { writer -> names.sorted().forEach { writer.appendLine(it) } }
        return true
    }

    private fun withNested(declaration: KSClassDeclaration): Sequence<KSClassDeclaration> =
        sequenceOf(declaration) + declaration.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested)

    private fun application(
        resolver: Resolver,
        configurations: List<Configuration<*>>,
    ) {
        val types = KspTypeOracle(resolver)
        val byApp = linkedMapOf<ClassName, MutableList<Configuration<*>>>()
        configurations.forEach { configuration ->
            val appJavaClass = configuration.applicationClass()
            if (appJavaClass == null) {
                logger.error("Could not determine KmpKtApplication type argument for ${configuration::class.qualifiedName}.")
                return
            }
            val appClassName = appJavaClass.toClassName()
            byApp.getOrPut(appClassName) { mutableListOf() } += configuration
        }

        val duplicates = byApp.filterValues { it.size > 1 }
        if (duplicates.isNotEmpty()) {
            duplicates.forEach { (app, configs) ->
                val names = configs.joinToString(", ") { it::class.qualifiedName ?: it::class.simpleName ?: "Configuration" }
                logger.error("Multiple Configuration implementations found for ${app.canonicalName}: $names.")
            }
            return
        }

        val compiledByApp = linkedMapOf<ClassName, CompiledContainer>()
        byApp.forEach { (app, configs) ->
            val compiled = compile(types, configurations = configs) ?: return
            val externals =
                compiled.definitions
                    .mapNotNull { (it.instantiation as? Instantiation.External)?.parameterName }
            if (externals.isNotEmpty()) {
                logger.error(
                    "KmpKt.run() for ${app.canonicalName} cannot be generated when external services are registered: " +
                        "${externals.joinToString(", ")}.",
                )
                return
            }
            compiledByApp[app] = compiled
        }

        ContainerGenerator(types::typeName)
            .generateApplications(compiledByApp)
            .writeTo(codeGenerator, Dependencies.ALL_FILES)
    }

    private fun Configuration<*>.applicationClass(): Class<*>? {
        var type: Type? = this::class.java
        while (type != null) {
            val raw = (type as? ParameterizedType)?.rawType as? Class<*> ?: type as? Class<*> ?: break
            raw.genericInterfaces.forEach { iface ->
                val param = iface as? ParameterizedType ?: return@forEach
                if (param.rawType == Configuration::class.java) {
                    val arg = param.actualTypeArguments.firstOrNull()
                    return (arg as? Class<*>) ?: (arg as? ParameterizedType)?.rawType as? Class<*>
                }
            }
            type = raw.superclass
        }
        return null
    }

    private fun Class<*>.toClassName(): ClassName {
        val packageName = `package`?.name.orEmpty()
        val simpleNames =
            name
                .removePrefix(if (packageName.isEmpty()) "" else "$packageName.")
                .split('$')
        return ClassName(packageName, simpleNames)
    }

    private fun compile(
        types: KspTypeOracle,
        symbol: KSAnnotated? = null,
        configurations: List<Configuration<*>> = ServiceLoader.load(Configuration::class.java, classLoader).toList(),
        configure: (ContainerBuilder) -> Unit = {},
    ): CompiledContainer? {
        val diagnostics = CollectingDiagnostics()
        val builder = ContainerBuilder(types, diagnostics).also(configure)

        val compiled =
            try {
                val components = ServiceLoader.load(Component::class.java, classLoader).toList()
                if (components.isEmpty() && configurations.isEmpty()) {
                    logger.warn(
                        "No Component and no Configuration on the processor's classpath, so this container has no services. " +
                            "Add the -di modules with ksp(project(\":…-di\")).",
                        symbol,
                    )
                }

                ContainerCompiler(components, configurations).compile(builder)
            } catch (failure: Throwable) {
                // Components, configurations and passes are user code running inside the compiler; one that throws
                // should fail this compilation with its own stack trace, not KSP's.
                logger.error("Compiling the container failed: ${failure.stackTraceToString()}", symbol)
                return null
            }

        diagnostics.warnings.forEach { logger.warn(it, symbol) }
        diagnostics.errors.forEach { logger.error(it, symbol) }
        return compiled
    }

    /** The name `ServiceLoader` wants: `Outer$Inner` for a nested class. */
    private fun KSClassDeclaration.binaryName(): String? {
        val qualified = qualifiedName?.asString() ?: return null
        val packageName = packageName.asString()
        val nested = qualified.removePrefix("$packageName.").replace('.', '$')

        return if (packageName.isEmpty()) nested else "$packageName.$nested"
    }
}
