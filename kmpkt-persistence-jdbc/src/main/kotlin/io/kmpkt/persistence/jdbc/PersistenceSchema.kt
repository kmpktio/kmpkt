package io.kmpkt.persistence.jdbc

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * This adapter's own tables - the event log, snapshots and the transports' tracking.
 *
 * Migrating is a separate call from building the pool so that the application can run this over the same
 * [DataSource] it uses for its own tables, rather than having to let this adapter own the connection.
 */
public object PersistenceSchema {
    /**
     * Our own directory, not Flyway's default `db/migration`: a classpath location is scanned across
     * modules, so an application putting its migrations in the default would have each Flyway instance find
     * both sets. Same reasoning as the history table - the library moves aside, the application configures
     * nothing.
     */
    private const val LOCATION = "classpath:db/kmpkt"

    /**
     * Our own history table, not Flyway's default: a library sharing a schema with the application that
     * uses it has no business taking the generic name. That leaves the default to the application, which
     * then has to configure nothing.
     */
    private const val HISTORY_TABLE = "kmpkt_schema_history"

    /**
     * Brings the schema up to date, creating what is missing.
     *
     * Safe to run in any order relative to an application's own Flyway over the same schema, which is what
     * the baseline settings are for: whichever instance runs second finds a non-empty schema with no history
     * table of its own, and would otherwise refuse outright. The baseline version is `0` rather than
     * Flyway's default of `1` - at the default, baselining counts this adapter's own `V1` as already applied
     * and skips it, which produces a green migration run and no `event` table.
     */
    public fun migrate(dataSource: DataSource) {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations(LOCATION)
            .table(HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()
    }
}
