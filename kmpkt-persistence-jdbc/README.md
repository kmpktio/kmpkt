# kmpkt-persistence-jdbc

JDBC-backed [`kmpkt-persistence`](../kmpkt-persistence) implementation (`JdbcPersistence`) for
SQLite and PostgreSQL using HikariCP and Flyway migrations (`AppendLog`, `KeyValueStore`,
`WorkQueue`).

## Usage

```kotlin
val persistence = JdbcPersistence.open(PersistenceConfig("jdbc:sqlite:app.db"))
```

Wire it in DI with `jdbcPersistence { dsn = env("DATABASE_URL", default = "jdbc:sqlite:app.db") }`.
