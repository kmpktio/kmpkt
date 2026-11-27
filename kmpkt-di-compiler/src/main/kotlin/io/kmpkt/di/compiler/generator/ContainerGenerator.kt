package io.kmpkt.di.compiler.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.CompiledContainer
import io.kmpkt.di.compiler.CompiledContext
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.Instantiation
import io.kmpkt.di.compiler.Scope
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.TypeRef

private val DURATION = ClassName("kotlin.time", "Duration")

/**
 * Writes a [CompiledContainer] out as a class: constructor calls, `lazy` properties and nothing else.
 *
 * - an external service is a constructor parameter
 * - a shared service is a private `by lazy` property
 * - a prototype is a private function
 * - an inlined service is the expression in its consumer's argument list
 *
 * [typeName] turns a [TypeRef] into what KotlinPoet imports; only the caller knows where a package ends and
 * a nested class begins.
 */
internal class ContainerGenerator(
    private val typeName: (TypeRef) -> TypeName,
) {
    fun generateApplications(applications: Map<ClassName, CompiledContainer>): FileSpec {
        val kmpKt = ClassName("io.kmpkt.di", "KmpKt")
        val kmpKtApp = ClassName("io.kmpkt.di", "KmpKtApplication")
        val defaultApp = kmpKtApp.nestedClass("Default")
        val javaClassOfApp =
            ClassName("java.lang", "Class").parameterizedBy(
                com.squareup.kotlinpoet.WildcardTypeName
                    .producerOf(kmpKtApp),
            )
        val stringArray = ClassName("kotlin", "Array").parameterizedBy(ClassName("kotlin", "String"))
        val containerNames = NameAllocator()
        val containersByApp = linkedMapOf<ClassName, ClassName>()
        val containerTypes = mutableListOf<TypeSpec>()

        applications.forEach { (appClass, compiled) ->
            val containerSimple = containerNames.newName(appClass.simpleNames.joinToString("") + "Container")
            val containerName = kmpKt.nestedClass(containerSimple)
            containersByApp[appClass] = containerName

            val writer = Writer(compiled, containerName)
            containerTypes +=
                writer
                    .typeBuilder(containerName, KModifier.PRIVATE)
                    .addFunction(
                        FunSpec
                            .builder("run")
                            .addParameter("args", stringArray)
                            .apply {
                                compiled.runners.forEach { runnerId ->
                                    addStatement("%L.run(args)", writer.reference(runnerId))
                                }
                            }.build(),
                    ).build()
        }

        val appParam = ParameterSpec.builder("application", javaClassOfApp).build()
        val argsParam =
            ParameterSpec
                .builder("args", stringArray)
                .defaultValue("emptyArray()")
                .build()

        val runByClass =
            FunSpec
                .builder("run")
                .addParameter(appParam)
                .addParameter(argsParam)
                .beginControlFlow("when (application)")
                .apply {
                    containersByApp.forEach { (appClass, containerName) ->
                        addStatement("%T::class.java -> %T().run(args)", appClass, containerName)
                    }
                }.addStatement("else -> error(%P)", "No Configuration<\${application.name}> registered.")
                .endControlFlow()
                .build()

        val reifiedA =
            com.squareup.kotlinpoet
                .TypeVariableName("A", kmpKtApp)
                .copy(reified = true)
        val jvmName =
            com.squareup.kotlinpoet.AnnotationSpec
                .builder(JvmName::class)
                .addMember("%S", "runApplication")
                .build()
        val runReified =
            FunSpec
                .builder("run")
                .addAnnotation(jvmName)
                .addModifiers(KModifier.INLINE)
                .addTypeVariable(reifiedA)
                .addParameter(argsParam)
                .addStatement("run(%T::class.java, args)", reifiedA)
                .build()

        val kmpKtBuilder =
            TypeSpec
                .objectBuilder(kmpKt)
                .addFunction(runByClass)
                .addFunction(runReified)

        if (defaultApp in containersByApp) {
            kmpKtBuilder.addFunction(
                FunSpec
                    .builder("run")
                    .addParameter(argsParam)
                    .addStatement("run(%T::class.java, args)", defaultApp)
                    .build(),
            )
        }

        containerTypes.forEach(kmpKtBuilder::addType)

        return FileSpec
            .builder(kmpKt)
            .indent("    ")
            .addFileComment("Generated by kmpkt-di. Do not edit.")
            .addType(kmpKtBuilder.build())
            .build()
    }

    private inner class Writer(
        private val compiled: CompiledContainer,
        private val containerClassName: ClassName,
        private val parentWriter: Writer? = null,
        private val contextInfo: CompiledContext? = null,
    ) {
        private val definitions = compiled.definitions.associateBy { it.id }
        private val names = NameAllocator()
        private val members: Map<ServiceId, String>
        private val contextClassNames: Map<String, ClassName>

        init {
            // Entry points first: their names are the contract's and cannot move.
            compiled.entryPoints.forEach { names.newName(it.name) }
            members =
                compiled.definitions
                    .filterNot { it.inlined || it.instantiation is Instantiation.Parent }
                    .associate { it.id to names.newName(suggestion(it)) }
            contextClassNames =
                compiled.contexts.associate { ctx ->
                    val raw =
                        ctx.name
                            .substringAfterLast('.')
                            .split(Regex("[^A-Za-z0-9]+"))
                            .filter { it.isNotEmpty() }
                            .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
                            .ifEmpty { "Context" } + "Container"
                    ctx.name to containerClassName.nestedClass(names.newName(raw))
                }
        }

        fun typeBuilder(
            className: ClassName,
            visibility: KModifier,
        ): TypeSpec.Builder {
            // By name, not in registration order: which component loads first must not reorder a constructor
            // that the application calls.
            val externals =
                compiled.definitions
                    .filter { it.instantiation is Instantiation.External }
                    .sortedBy { (it.instantiation as Instantiation.External).parameterName }
            val constructor = FunSpec.constructorBuilder()
            val type = TypeSpec.classBuilder(className).addModifiers(visibility)

            if (parentWriter != null) {
                constructor.addParameter("parent", parentWriter.containerClassName)
                type.addProperty(
                    PropertySpec
                        .builder("parent", parentWriter.containerClassName, KModifier.PRIVATE)
                        .initializer("parent")
                        .build(),
                )
            }
            if (contextInfo != null) {
                type.addSuperinterface(typeName(contextInfo.handleType))
                contextInfo.views.forEach { view ->
                    type.addSuperinterface(typeName(view))
                }
            }

            externals.forEach { external ->
                val parameter = (external.instantiation as Instantiation.External).parameterName
                constructor.addParameter(parameter, typeName(external.type))
                type.addProperty(
                    PropertySpec
                        .builder(members.getValue(external.id), typeName(external.type), KModifier.PRIVATE)
                        .initializer("%N", parameter)
                        .build(),
                )
            }
            if (externals.isNotEmpty() || parentWriter != null) {
                type.primaryConstructor(constructor.build())
            }

            if (contextInfo != null) {
                val autoCloseable = ClassName("java.lang", "AutoCloseable")
                val mutableListOfAutoCloseable =
                    ClassName("kotlin.collections", "MutableList").parameterizedBy(autoCloseable)
                type.addProperty(
                    PropertySpec
                        .builder("closeables", mutableListOfAutoCloseable, KModifier.PRIVATE)
                        .initializer("mutableListOf()")
                        .build(),
                )
                type.addProperty(
                    PropertySpec
                        .builder("closed", ClassName("kotlin", "Boolean"), KModifier.PRIVATE)
                        .addAnnotation(ClassName("kotlin.jvm", "Volatile"))
                        .mutable(true)
                        .initializer("false")
                        .build(),
                )
            }

            compiled.entryPoints.forEach { entryPoint ->
                type.addProperty(
                    PropertySpec
                        .builder(entryPoint.name, typeName(entryPoint.type), KModifier.OVERRIDE)
                        .getter(FunSpec.getterBuilder().addStatement("return %L", reference(entryPoint.id)).build())
                        .build(),
                )
            }

            compiled.definitions
                .filterNot { it.inlined || it.instantiation is Instantiation.External || it.instantiation is Instantiation.Parent }
                .forEach { definition ->
                    when (definition.scope) {
                        Scope.Shared -> type.addProperty(shared(definition))
                        Scope.Prototype -> type.addFunction(prototype(definition))
                    }
                }

            val eager =
                compiled.definitions.filter {
                    !it.inlined &&
                        it.instantiation !is Instantiation.External &&
                        it.instantiation !is Instantiation.Parent &&
                        it.scope == Scope.Shared &&
                        !it.lazy
                }
            if (eager.isNotEmpty()) {
                type.addInitializerBlock(
                    CodeBlock.builder().apply { eager.forEach { addStatement("%N", members.getValue(it.id)) } }.build(),
                )
            }

            if (contextInfo != null) {
                type.addFunction(trackFunction(contextInfo))
                type.addFunction(closeFunction(contextInfo))
            }

            compiled.contexts.forEach { ctx ->
                val childName = contextClassNames.getValue(ctx.name)
                val childWriter = Writer(ctx.compiled, childName, parentWriter = this, contextInfo = ctx)
                type.addType(childWriter.typeBuilder(childName, KModifier.PRIVATE).build())
            }

            return type
        }

        private fun trackFunction(ctx: CompiledContext): FunSpec {
            val typeVar = com.squareup.kotlinpoet.TypeVariableName("T")
            return FunSpec
                .builder("track")
                .addModifiers(KModifier.PRIVATE)
                .addTypeVariable(typeVar)
                .addParameter("instance", typeVar)
                .returns(typeVar)
                .beginControlFlow("if (instance is AutoCloseable)")
                .beginControlFlow("synchronized(closeables)")
                .beginControlFlow("if (closed)")
                .addStatement("instance.close()")
                .addStatement("check(false) { %S }", "Context \"${ctx.name}\" is closed")
                .endControlFlow()
                .addStatement("closeables += instance")
                .endControlFlow()
                .endControlFlow()
                .addStatement("return instance")
                .build()
        }

        private fun closeFunction(ctx: CompiledContext): FunSpec =
            FunSpec
                .builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .addStatement(
                    "val toClose = synchronized(closeables) {\n⇥if (closed) return\nclosed = true\ncloseables.reversed().also { closeables.clear() }\n⇤}",
                ).addStatement("var failure: Throwable? = null")
                .beginControlFlow("for (closeable in toClose)")
                .beginControlFlow("try")
                .addStatement("closeable.close()")
                .nextControlFlow("catch (e: Throwable)")
                .addStatement("if (failure == null) failure = e else failure.addSuppressed(e)")
                .endControlFlow()
                .endControlFlow()
                .apply {
                    val factoryId = ctx.factoryId
                    if (factoryId != null && parentWriter != null) {
                        beginControlFlow("try")
                        addStatement("parent.%L.destroy(context)", parentWriter.reference(factoryId))
                        nextControlFlow("catch (e: Throwable)")
                        addStatement("if (failure == null) failure = e else failure.addSuppressed(e)")
                        endControlFlow()
                    }
                }.addStatement("if (failure != null) throw failure")
                .build()

        private fun shared(definition: ServiceDefinition): PropertySpec {
            val body =
                if (contextInfo != null) {
                    CodeBlock.of(
                        "lazy {\n⇤check(!closed) { %S }\ntrack(%L)\n⇤}⇥⇥",
                        "Context \"${contextInfo.name}\" is closed",
                        instantiate(definition),
                    )
                } else {
                    CodeBlock.of("lazy {\n⇤%L\n⇤}⇥⇥", instantiate(definition))
                }
            return PropertySpec
                .builder(members.getValue(definition.id), typeName(definition.type), KModifier.PRIVATE)
                // KotlinPoet indents a delegate twice as a continuation; the block is pulled back so that its
                // body sits one level below the property, as a human would write it.
                .delegate(body)
                .build()
        }

        private fun prototype(definition: ServiceDefinition): FunSpec =
            FunSpec
                .builder(members.getValue(definition.id))
                .addModifiers(KModifier.PRIVATE)
                .returns(typeName(definition.type))
                .apply {
                    if (contextInfo != null) {
                        addStatement("check(!closed) { %S }", "Context \"${contextInfo.name}\" is closed")
                        addStatement("return track(%L)", instantiate(definition))
                    } else {
                        addStatement("return %L", instantiate(definition))
                    }
                }.build()

        /**
         * A service named after its type is called by the type's simple name. An id somebody chose -
         * `messenger.bus.command.handlers` - keeps what tells it apart: `busCommandHandlers`.
         */
        private fun suggestion(definition: ServiceDefinition): String {
            val id = definition.id.value
            val words =
                if (id.startsWith(definition.type.qualifiedName)) {
                    listOf(definition.type.qualifiedName.substringAfterLast('.'))
                } else {
                    id.substringBefore('<').split('.').let { if (it.size > 1) it.drop(1) else it }
                }
            val simple =
                words
                    .map { word -> word.filter { it.isLetterOrDigit() } }
                    .filter { it.isNotEmpty() }
                    .joinToString("") { word -> word.replaceFirstChar { it.uppercase() } }
                    .replaceFirstChar { it.lowercase() }
                    .ifEmpty { "service" }

            return when {
                definition.instantiation is Instantiation.External -> (definition.instantiation as Instantiation.External).parameterName
                definition.scope == Scope.Prototype -> "new" + simple.replaceFirstChar { it.uppercase() }
                else -> simple
            }
        }

        fun reference(id: ServiceId): CodeBlock {
            val definition = definitions.getValue(id)

            return when {
                definition.instantiation is Instantiation.Parent && parentWriter != null ->
                    CodeBlock.of("parent.%L", parentWriter.reference((definition.instantiation as Instantiation.Parent).id))
                definition.inlined -> instantiate(definition)
                definition.instantiation is Instantiation.External || definition.scope == Scope.Shared ->
                    CodeBlock.of("%N", members.getValue(id))
                else -> CodeBlock.of("%N()", members.getValue(id))
            }
        }

        private fun instantiate(definition: ServiceDefinition): CodeBlock {
            if (definition.instantiation is Instantiation.ContextScope) {
                val ctx = compiled.contexts.first { it.name == (definition.instantiation as Instantiation.ContextScope).name }
                val childName = contextClassNames.getValue(ctx.name)
                val scopeType = typeName(definition.type)
                val inputType = typeName(ctx.inputType)
                val handleType = typeName(ctx.handleType)
                val contextExpr =
                    if (ctx.factoryId != null) {
                        CodeBlock.of("%L.create(input)", reference(ctx.factoryId!!))
                    } else {
                        CodeBlock.of("input")
                    }
                return CodeBlock.of(
                    "object : %T {\n⇥override fun open(input: %T): %T = %T(this@%N, %L)\n⇤}",
                    scopeType,
                    inputType,
                    handleType,
                    childName,
                    containerClassName.simpleName,
                    contextExpr,
                )
            }
            val arguments =
                definition.arguments
                    .filterValues { it != Argument.Default }
                    .map { (name, argument) -> joined(CodeBlock.of("%N =", name), write(argument)) }
            val call =
                when (val instantiation = definition.instantiation) {
                    is Instantiation.Constructor -> CodeBlock.of("%T", typeName(definition.type))
                    is Instantiation.Function -> CodeBlock.of("%M", MemberName(instantiation.packageName, instantiation.name))
                    is Instantiation.ObjectFunction -> CodeBlock.of("%T.%N", typeName(instantiation.owner), instantiation.name)
                    is Instantiation.StaticFactory -> CodeBlock.of("%T.%N", typeName(instantiation.owner), instantiation.name)
                    is Instantiation.External, is Instantiation.Parent, is Instantiation.ContextScope ->
                        error("${definition.describe()} is never instantiated directly")
                }

            return CodeBlock.of("%L%L", call, parenthesized(arguments))
        }

        /** A value that spans lines starts on a line of its own, below what it is assigned to. */
        private fun joined(
            head: CodeBlock,
            value: CodeBlock,
        ): CodeBlock = if ('\n' in value.toString()) CodeBlock.of("%L\n⇥%L⇤", head, value) else CodeBlock.of("%L %L", head, value)

        /** One argument per line with a trailing comma, the way this code base writes calls. */
        private fun parenthesized(items: List<CodeBlock>): CodeBlock =
            if (items.isEmpty()) {
                CodeBlock.of("()")
            } else {
                CodeBlock.of("(\n⇥%L,\n⇤)", items.joinToCode(",\n"))
            }

        private fun write(argument: Argument): CodeBlock =
            when (argument) {
                is Argument.Service -> reference(argument.reference.resolved())
                is Argument.Deferred -> {
                    val body = write(argument.value)
                    val prefix = if (argument.kind == Argument.Deferred.Kind.Lazy) "lazy " else ""
                    if ('\n' in body.toString()) {
                        CodeBlock.of("%L{\n⇥%L\n⇤}", prefix, body)
                    } else {
                        CodeBlock.of("%L{ %L }", prefix, body)
                    }
                }
                is Argument.Sequence ->
                    if (argument.items.isEmpty()) {
                        val element = argument.elementType
                        if (element != null) {
                            CodeBlock.of("emptyList<%T>()", typeName(element))
                        } else {
                            CodeBlock.of("emptyList()")
                        }
                    } else {
                        CodeBlock.of("listOf%L", parenthesized(argument.items.map(::write)))
                    }
                is Argument.Dictionary ->
                    if (argument.entries.isEmpty()) {
                        CodeBlock.of("emptyMap()")
                    } else {
                        CodeBlock.of(
                            "mapOf%L",
                            parenthesized(argument.entries.map { (key, value) -> joined(CodeBlock.of("%L to", write(key)), write(value)) }),
                        )
                    }
                is Argument.Literal -> write(argument.value)
                is Argument.Environment ->
                    when (val default = argument.default) {
                        is ConstValue.Number -> CodeBlock.of("(System.getenv(%S)?.toInt() ?: %L)", argument.name, write(default))
                        is ConstValue.Text -> CodeBlock.of("(System.getenv(%S) ?: %L)", argument.name, write(default))
                        else ->
                            CodeBlock.of(
                                "(System.getenv(%S) ?: error(%S))",
                                argument.name,
                                "Environment variable ${argument.name} is not set",
                            )
                    }
                is Argument.Tagged, Argument.Inner, Argument.Default ->
                    error("$argument should have been resolved before generation")
            }

        private fun write(value: ConstValue): CodeBlock =
            when (value) {
                is ConstValue.Null -> CodeBlock.of("null")
                is ConstValue.Text -> CodeBlock.of("%S", value.value)
                is ConstValue.Flag -> CodeBlock.of("%L", value.value)
                is ConstValue.Number ->
                    when (value.value) {
                        is Long -> CodeBlock.of("%LL", value.value)
                        is Float -> CodeBlock.of("%Lf", value.value)
                        else -> CodeBlock.of("%L", value.value)
                    }
                is ConstValue.Duration -> CodeBlock.of("%T.parseIsoString(%S)", DURATION, value.value.toIsoString())
                is ConstValue.EnumEntry -> CodeBlock.of("%T.%N", typeName(value.type), value.entry)
                is ConstValue.ClassLiteral -> CodeBlock.of("%T::class", typeName(value.type.copy(arguments = emptyList())))
            }

        private fun ServiceReference.resolved(): ServiceId =
            when (this) {
                is ServiceReference.ById -> id
                is ServiceReference.ByKey -> error("$key should have been resolved before generation")
            }
    }
}
