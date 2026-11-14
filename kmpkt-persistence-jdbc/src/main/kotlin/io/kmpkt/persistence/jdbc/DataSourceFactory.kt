package io.kmpkt.persistence.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

public object DataSourceFactory {
    /**
     * A connection pool for [config], and nothing else. Migrating is [PersistenceSchema.migrate]'s job.
     *
     * The two are separate so that an application can own its JDBC setup: it builds one pool, hands it here
     * for this adapter's schema and uses the same pool for its own tables. A factory that also migrated
     * would leave an application that wants its own tables no choice but to build a second pool - and two
     * pools on one SQLite file are two writers, which is exactly what [maximumPoolSize] is set to 1 to
     * prevent.
     */
    public fun create(config: PersistenceConfig): DataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                // SQLite allows one writer at a time. One connection serializes writes instead of failing with SQLITE_BUSY.
                maximumPoolSize = if (config.isSqlite) 1 else 5
            },
        )

    /** Creates a connection pool for [config] and applies [PersistenceSchema.migrate]. */
    public fun createAndMigrate(config: PersistenceConfig): DataSource =
        create(config).also(PersistenceSchema::migrate)
}
