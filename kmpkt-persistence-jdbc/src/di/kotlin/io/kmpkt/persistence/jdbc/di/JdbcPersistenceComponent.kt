package io.kmpkt.persistence.jdbc.di

import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.persistence.Persistence
import io.kmpkt.persistence.di.PersistenceComponent
import io.kmpkt.persistence.jdbc.DataSourceFactory
import io.kmpkt.persistence.jdbc.JdbcPersistence
import io.kmpkt.persistence.jdbc.PersistenceConfig
import kotlin.reflect.KClass

public class JdbcPersistenceComponent : Component<JdbcPersistenceOptions> {
    override val requires: Set<KClass<out Component<*>>> = setOf(PersistenceComponent::class)

    override fun defaults(): JdbcPersistenceOptions = JdbcPersistenceOptions()

    override fun ContainerBuilder.register(config: JdbcPersistenceOptions) {
        config.dsn?.let { dsn ->
            service<PersistenceConfig> {
                argument("databaseUrl", dsn)
            }
        }
        if (config.migrate) {
            factory(DataSourceFactory::createAndMigrate)
        } else {
            factory(DataSourceFactory::create)
        }
        service<JdbcPersistence> { bind<Persistence>() }
    }
}
