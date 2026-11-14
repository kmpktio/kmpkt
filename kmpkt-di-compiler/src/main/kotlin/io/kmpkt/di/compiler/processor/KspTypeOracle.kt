package io.kmpkt.di.compiler.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isInternal
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toClassName
import io.kmpkt.di.compiler.ClassInfo
import io.kmpkt.di.compiler.ClassTrait
import io.kmpkt.di.compiler.Instantiation
import io.kmpkt.di.compiler.Parameter
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.TypeOracle
import io.kmpkt.di.compiler.TypeRef
import java.io.File
import java.net.JarURLConnection
import java.net.URI

/**
 * The [TypeOracle] over what the application module compiles against. A name is looked up on the compile
 * classpath, so a type the application cannot see is a type the generated container could not name either -
 * and is reported as such instead of producing code that does not compile.
 */
internal class KspTypeOracle(
    private val resolver: Resolver,
    private val classLoader: ClassLoader = KspTypeOracle::class.java.classLoader,
) : TypeOracle {
    override fun isAssignable(
        from: TypeRef,
        to: TypeRef,
    ): Boolean {
        val target = resolve(to) ?: return false
        val source = resolve(from) ?: return false

        return target.isAssignableFrom(source)
    }

    override fun typeArguments(
        type: TypeRef,
        of: TypeRef,
    ): List<TypeArgument> =
        // A service registered as the supertype itself - a factory returning `MessageHandler<Ping, Unit>` -
        // carries the arguments in its own type.
        if (type.qualifiedName == of.qualifiedName) {
            type.arguments
        } else {
            declaration(type)
                ?.let { supertypesOf(it, type.arguments) }
                ?.firstOrNull { it.qualifiedName == of.qualifiedName }
                ?.arguments
                .orEmpty()
        }

    /**
     * Every supertype of [declaration], each with its type arguments as they come out for this particular
     * class: for `class Handler : CommandHandler<SortList>` over `CommandHandler<C> : MessageHandler<C, Unit>`
     * that is `MessageHandler<SortList, Unit>`, not `MessageHandler<C, Unit>`.
     */
    private fun supertypesOf(
        declaration: KSClassDeclaration,
        arguments: List<TypeArgument>,
    ): Sequence<TypeRef> {
        val bindings =
            declaration.typeParameters
                .map { it.name.asString() }
                .zip(arguments)
                .toMap()

        return declaration.superTypes.flatMap { reference ->
            val supertype = reference.resolve()
            val parent = supertype.declaration as? KSClassDeclaration
            val name = parent?.qualifiedName?.asString()

            if (parent == null || name == null) {
                emptySequence()
            } else {
                val parentArguments = supertype.arguments.map { it.toTypeArgument(bindings) }
                sequenceOf(TypeRef(name, parentArguments)) + supertypesOf(parent, parentArguments)
            }
        }
    }

    override fun parameters(
        type: TypeRef,
        instantiation: Instantiation,
    ): List<Parameter>? {
        val callable =
            when (instantiation) {
                is Instantiation.Constructor -> constructor(type)
                is Instantiation.Function -> function(type, instantiation)
                is Instantiation.ObjectFunction -> objectFunction(instantiation)
                is Instantiation.StaticFactory -> staticFactory(type, instantiation)
                is Instantiation.External -> null
            }

        return callable?.parameters?.map { parameter ->
            Parameter(
                name = parameter.name?.asString() ?: return null,
                type = parameter.type.resolve().toTypeRef(),
                hasDefault = parameter.hasDefault,
            )
        }
    }

    @OptIn(KspExperimental::class)
    override fun classesIn(packageName: String): List<ClassInfo> =
        packagesUnder(packageName)
            .asSequence()
            .flatMap { pkg -> resolver.getDeclarationsFromPackage(pkg) }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap(::withNested)
            .distinctBy { it.qualifiedName?.asString() }
            // A container generated in an earlier round is the application's product, not one of its services.
            .filterNot { declaration -> declaration.getAllSuperTypes().any { it.declaration.isContainer() } }
            .mapNotNull { declaration ->
                val name = declaration.qualifiedName?.asString() ?: return@mapNotNull null
                val supertypes = supertypesOf(declaration, emptyList()).distinct().toList()

                ClassInfo(
                    type = TypeRef(name),
                    interfaces = supertypes.filter { declaration(it)?.classKind == ClassKind.INTERFACE },
                    traits = traitsOf(declaration, supertypes),
                )
            }.toList()

    private fun packagesUnder(rootPackage: String): Set<String> =
        buildSet {
            add(rootPackage)
            val prefix = "$rootPackage."

            // 1. Source files in the current compilation
            resolver.getAllFiles().forEach { file ->
                val pkg = file.packageName.asString()
                if (pkg == rootPackage || pkg.startsWith(prefix)) add(pkg)
            }

            // 2. Compiled classes on the classpath (e.g. modular Gradle subprojects)
            val basePath = rootPackage.replace('.', '/')
            val resources = classLoader.getResources(basePath)
            while (resources.hasMoreElements()) {
                val url = resources.nextElement()
                when (url.protocol) {
                    "file" -> {
                        val rootDir = File(URI(url.toString()))
                        rootDir.walkTopDown().filter { it.isDirectory }.forEach { dir ->
                            val relative = dir.relativeTo(rootDir).path
                            if (relative.isNotEmpty()) {
                                add("$rootPackage.${relative.replace(File.separatorChar, '.')}")
                            }
                        }
                    }
                    "jar" -> {
                        val connection = url.openConnection() as JarURLConnection
                        val jarPrefix = "$basePath/"
                        connection.jarFile.entries().asSequence().forEach { entry ->
                            if (!entry.isDirectory && entry.name.startsWith(jarPrefix) && entry.name.endsWith(".class")) {
                                val pkg = entry.name.substringBeforeLast('/').replace('/', '.')
                                if (pkg == rootPackage || pkg.startsWith(prefix)) add(pkg)
                            }
                        }
                    }
                }
            }
        }

    private fun withNested(declaration: KSClassDeclaration): Sequence<KSClassDeclaration> =
        sequenceOf(declaration) + declaration.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested)

    private fun KSDeclaration.isContainer(): Boolean =
        annotations.any {
            it.annotationType
                .resolve()
                .declaration.qualifiedName
                ?.asString() == CONTAINER
        }

    private fun traitsOf(
        declaration: KSClassDeclaration,
        supertypes: List<TypeRef>,
    ): Set<ClassTrait> =
        buildSet {
            when (declaration.classKind) {
                ClassKind.INTERFACE -> add(ClassTrait.Interface)
                ClassKind.ENUM_CLASS, ClassKind.ENUM_ENTRY -> add(ClassTrait.Enum)
                ClassKind.OBJECT -> add(ClassTrait.Object)
                ClassKind.ANNOTATION_CLASS -> add(ClassTrait.Annotation)
                ClassKind.CLASS -> Unit
            }
            if (Modifier.SEALED in declaration.modifiers) add(ClassTrait.Sealed)
            if (declaration.classKind == ClassKind.CLASS && declaration.isAbstract()) add(ClassTrait.Abstract)
            if (Modifier.DATA in declaration.modifiers) add(ClassTrait.Data)
            if (supertypes.any { it.qualifiedName == "kotlin.Throwable" }) add(ClassTrait.Throwable)
            if (declaration.typeParameters.isNotEmpty()) add(ClassTrait.Generic)
            if (!declaration.isCallable() || Modifier.INNER in declaration.modifiers) add(ClassTrait.Unreachable)
        }

    /**
     * Whether the generated container may name this. Public always; internal as well when it is declared in
     * the module being compiled, which is the module the container is generated into.
     */
    private fun KSDeclaration.isCallable(): Boolean = isPublic() || (isInternal() && containingFile != null)

    /** What KotlinPoet needs to import [type]. */
    fun typeName(type: TypeRef): TypeName {
        val className = declaration(type)?.toClassName() ?: ClassName.bestGuess(type.qualifiedName)
        val arguments =
            type.arguments.map { argument ->
                when (argument) {
                    is TypeArgument.Star -> STAR
                    is TypeArgument.Of -> typeName(argument.type)
                }
            }

        return (if (arguments.isEmpty()) className else className.parameterizedBy(arguments)).copy(nullable = type.nullable)
    }

    private fun constructor(type: TypeRef): KSFunctionDeclaration? {
        val declaration = declaration(type) ?: return null
        if (declaration.classKind != ClassKind.CLASS || declaration.isAbstract() || !declaration.isCallable()) return null

        // A Java class has no primary constructor; a single public one is unambiguous all the same.
        val constructor = declaration.primaryConstructor ?: declaration.getConstructors().singleOrNull()

        return constructor?.takeIf { it.isCallable() }
    }

    private fun function(
        type: TypeRef,
        instantiation: Instantiation.Function,
    ): KSFunctionDeclaration? {
        val name = resolver.getKSNameFromString(instantiation.packageName + "." + instantiation.name)
        val returns = resolve(type) ?: return null

        return resolver
            .getFunctionDeclarationsByName(name, includeTopLevel = true)
            .filter { it.isPublic() && it.returnType?.resolve()?.let(returns::isAssignableFrom) == true }
            .singleOrNull()
    }

    private fun objectFunction(instantiation: Instantiation.ObjectFunction): KSFunctionDeclaration? {
        val owner = declaration(instantiation.owner) ?: return null
        if (owner.classKind != ClassKind.OBJECT || !owner.isPublic()) return null

        return owner
            .getAllFunctions()
            .filter { it.simpleName.asString() == instantiation.name && it.isPublic() }
            .singleOrNull()
    }

    private fun staticFactory(
        type: TypeRef,
        instantiation: Instantiation.StaticFactory,
    ): KSFunctionDeclaration? {
        val owner = declaration(instantiation.owner) ?: return null
        val returns = resolve(type) ?: return null
        if (!owner.isPublic()) return null

        // Statics are not members in Kotlin's sense: they are among the class's declarations, not its functions.
        return owner.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.simpleName.asString() == instantiation.name && it.isPublic() && Modifier.JAVA_STATIC in it.modifiers }
            .filter { it.returnType?.resolve()?.let(returns::isAssignableFrom) == true }
            .singleOrNull()
    }

    private fun declaration(type: TypeRef): KSClassDeclaration? =
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(type.qualifiedName))

    private fun resolve(type: TypeRef): KSType? {
        val declaration = declaration(type) ?: return null
        val resolved =
            if (type.arguments.isEmpty()) {
                declaration.asStarProjectedType()
            } else {
                declaration.asType(type.arguments.map { argument(it) ?: return null })
            }

        return if (type.nullable) resolved.makeNullable() else resolved
    }

    private fun argument(argument: TypeArgument): KSTypeArgument? =
        when (argument) {
            is TypeArgument.Star ->
                resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(resolver.builtIns.anyType), Variance.STAR)
            is TypeArgument.Of ->
                resolve(argument.type)?.let {
                    resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(it), Variance.INVARIANT)
                }
        }
}

private const val CONTAINER = "io.kmpkt.di.Container"

/**
 * The model's view of a KSP type. A type alias is followed to what it stands for; use-site variance is
 * dropped, since a service is bound to `List<Handler>`, not to `List<out Handler>`.
 */
internal fun KSType.toTypeRef(bindings: Map<String, TypeArgument> = emptyMap()): TypeRef {
    val aliased = (declaration as? KSTypeAlias)?.type?.resolve()
    if (aliased != null) {
        return aliased.toTypeRef(bindings).copy(nullable = isMarkedNullable || aliased.isMarkedNullable)
    }

    return TypeRef(
        qualifiedName = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString(),
        arguments = arguments.map { it.toTypeArgument(bindings) },
        nullable = isMarkedNullable,
    )
}

/** A type parameter is replaced by what [bindings] says it stands for here, and is `*` where nothing does. */
private fun KSTypeArgument.toTypeArgument(bindings: Map<String, TypeArgument> = emptyMap()): TypeArgument {
    val resolved = type?.resolve()
    val parameter = resolved?.declaration as? KSTypeParameter

    return when {
        variance == Variance.STAR || resolved == null -> TypeArgument.Star
        parameter != null -> bindings[parameter.name.asString()] ?: TypeArgument.Star
        else -> TypeArgument.Of(resolved.toTypeRef(bindings))
    }
}
