package io.kmpkt.persistence.jdbc.di

import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.literal

public class JdbcPersistenceOptions {
    public var dsn: Argument? = null
    public var migrate: Boolean = true

    public var databaseUrl: String?
        get() = null
        set(value) {
            dsn = value?.let(::literal)
        }
}

public fun ContainerBuilder.jdbcPersistence(block: JdbcPersistenceOptions.() -> Unit = {}) {
    configure(JdbcPersistenceComponent::class, block)
}
