package io.kmpkt.di.compiler.fixture

import io.kmpkt.di.compiler.ClassInfo
import io.kmpkt.di.compiler.ClassTrait
import io.kmpkt.di.compiler.Instantiation
import io.kmpkt.di.compiler.Parameter
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.TypeOracle
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.di.compiler.typeRef

/**
 * A [TypeOracle] that knows what it was told, in place of the one the processor builds over KSP. For unit
 * tests of the pipeline, and of a bundle: declare the classes it registers, compile, look at the result.
 */
public class FakeTypeOracle : TypeOracle {
    private class Shape(
        val supertypes: List<TypeRef>,
        val parameters: List<Parameter>?,
    )

    private val shapes = LinkedHashMap<String, Shape>()
    private val traits = mutableMapOf<String, Set<ClassTrait>>()
    private val functions = mutableMapOf<String, List<Parameter>>()

    /** [parameters] of `null` declares a type the generated container could not construct. */
    public fun declare(
        type: TypeRef,
        parameters: List<Parameter>? = emptyList(),
        supertypes: List<TypeRef> = emptyList(),
    ) {
        shapes[type.qualifiedName] = Shape(supertypes, parameters)
    }

    public inline fun <reified T> declare(
        vararg parameters: Parameter,
        supertypes: List<TypeRef> = emptyList(),
    ) {
        declare(typeRef<T>(), parameters.toList(), supertypes)
    }

    /** What [type] is besides a plain class, for `discover` to judge. */
    public fun declareTraits(
        type: TypeRef,
        vararg traits: ClassTrait,
    ) {
        this.traits[type.qualifiedName] = traits.toSet()
    }

    public fun declareFunction(
        qualifiedName: String,
        vararg parameters: Parameter,
    ) {
        functions[qualifiedName] = parameters.toList()
    }

    override fun isAssignable(
        from: TypeRef,
        to: TypeRef,
    ): Boolean =
        from.nonNull() == to.nonNull() ||
            (from.qualifiedName == to.qualifiedName && to.arguments.all { it == TypeArgument.Star }) ||
            shapes[from.qualifiedName]?.supertypes.orEmpty().any { isAssignable(it, to) }

    override fun typeArguments(
        type: TypeRef,
        of: TypeRef,
    ): List<TypeArgument> =
        (listOf(type) + shapes[type.qualifiedName]?.supertypes.orEmpty())
            .firstOrNull { it.qualifiedName == of.qualifiedName }
            ?.arguments
            .orEmpty()

    /** Every declared type of exactly that package, with all the supertypes it was declared with as its interfaces. */
    override fun classesIn(packageName: String): List<ClassInfo> =
        shapes
            .filterKeys { it.substringBeforeLast('.') == packageName }
            .map { (name, shape) -> ClassInfo(TypeRef(name), allSupertypes(shape), traits[name].orEmpty()) }

    private fun allSupertypes(shape: Shape): List<TypeRef> =
        shape.supertypes.flatMap { listOf(it) + shapes[it.qualifiedName]?.let(::allSupertypes).orEmpty() }.distinct()

    override fun parameters(
        type: TypeRef,
        instantiation: Instantiation,
    ): List<Parameter>? =
        when (instantiation) {
            is Instantiation.Constructor -> shapes[type.qualifiedName]?.parameters
            is Instantiation.Function -> functions["${instantiation.packageName}.${instantiation.name}"]
            is Instantiation.ObjectFunction -> functions["${instantiation.owner.qualifiedName}.${instantiation.name}"]
            is Instantiation.StaticFactory -> functions["${instantiation.owner.qualifiedName}.${instantiation.name}"]
            is Instantiation.External -> null
        }
}

public inline fun <reified T> parameter(
    name: String,
    hasDefault: Boolean = false,
): Parameter = Parameter(name, typeRef<T>(), hasDefault)
