package io.kmpkt.persistence.jdbc

public data class PersistenceConfig(
    /** JDBC DSN, e.g. `jdbc:sqlite:/data/app.db` or `jdbc:postgresql://host/db?user=…&password=…`. */
    public val databaseUrl: String,
) {
    public val isSqlite: Boolean get() = databaseUrl.startsWith("jdbc:sqlite:")
}
