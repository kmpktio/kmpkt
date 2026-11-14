package io.kmpkt.di.compiler

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * A type by name, with its type arguments.
 *
 * The model never holds a `KClass` or a KSP type. A `KClass` exists only for classes on the processor's
 * classpath, a KSP type only for one processing round; a name survives both, and what a name cannot answer
 * - is this a subtype of that - goes to the [TypeOracle].
 */
public data class TypeRef(
    val qualifiedName: String,
    val arguments: List<TypeArgument> = emptyList(),
    val nullable: Boolean = false,
) {
    /** The same type without its `?`. A service is never registered as nullable; a parameter may ask so. */
    public fun nonNull(): TypeRef = if (nullable) copy(nullable = false) else this

    override fun toString(): String {
        val rendered = if (arguments.isEmpty()) "" else arguments.joinToString(prefix = "<", postfix = ">")

        return qualifiedName + rendered + if (nullable) "?" else ""
    }

    public companion object {
        /**
         * Reads a type the way [toString] writes it: `com.acme.Repository<com.acme.Order>`.
         *
         * For the one place [typeRef] cannot reach: a configuration is compiled before the application it
         * configures, so it can name the application's own classes only like this.
         */
        public fun parse(text: String): TypeRef {
            val parser = Parser(text.filterNot { it.isWhitespace() })

            return parser.type().also { parser.end() }
        }
    }

    private class Parser(
        private val text: String,
    ) {
        private var position = 0

        fun type(): TypeRef {
            val start = position
            while (position < text.length && text[position] !in "<>,?") position++
            val name = text.substring(start, position)
            require(name.isNotEmpty() && name != "*") { "Expected a type name at position $start of \"$text\"" }

            val arguments = if (take('<')) arguments() else emptyList()

            return TypeRef(name, arguments, nullable = take('?'))
        }

        fun end() {
            require(position == text.length) { "Unexpected \"${text.substring(position)}\" at the end of \"$text\"" }
        }

        private fun arguments(): List<TypeArgument> {
            val arguments = mutableListOf<TypeArgument>()
            do {
                arguments += if (take('*')) TypeArgument.Star else TypeArgument.Of(type())
            } while (take(','))
            require(take('>')) { "Expected \">\" at position $position of \"$text\"" }

            return arguments
        }

        private fun take(character: Char): Boolean = (position < text.length && text[position] == character).also { if (it) position++ }
    }
}

public sealed interface TypeArgument {
    /** `*` */
    public data object Star : TypeArgument {
        override fun toString(): String = "*"
    }

    public data class Of(
        val type: TypeRef,
    ) : TypeArgument {
        override fun toString(): String = type.toString()
    }
}

/**
 * The typed way to write a [TypeRef]: `typeRef<DomainEventHandler<*>>()`.
 *
 * Works for any class the calling module can see, which for a `-di` companion module is its feature module's.
 */
public inline fun <reified T> typeRef(): TypeRef = typeOf<T>().toTypeRef()

@PublishedApi
internal fun KType.toTypeRef(): TypeRef {
    val kClass = classifier as? KClass<*> ?: throw IllegalArgumentException("$this is a type parameter, not a type")
    val name = kClass.qualifiedName ?: throw IllegalArgumentException("$this is local or anonymous and has no name")

    return TypeRef(
        qualifiedName = name,
        arguments = arguments.map { argument -> argument.type?.let { TypeArgument.Of(it.toTypeRef()) } ?: TypeArgument.Star },
        nullable = isMarkedNullable,
    )
}
