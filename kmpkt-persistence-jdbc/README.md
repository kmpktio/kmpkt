# kmpkt-persistence-jdbc

JDBC-backed KmpKt persistence implementation for SQLite and PostgreSQL using HikariCP connection pooling and Flyway migrations.

## Building Blocks

| Symbol | Role |
| --- | --- |
| `JdbcPersistence` | `Persistence` implementation backed by a `DataSource`, with `Dispatchers.IO` transactions and advisory or mutex locks. |
| `PersistenceConfig` | Configuration holding the JDBC URL and SQLite flag. |
| `DataSourceFactory` | Creates and optionally migrates HikariCP `DataSource` instances. |
| `PersistenceSchema` | Runs Flyway migrations from `classpath:db/kmpkt` into the `kmpkt_schema_history` table. |
| `JdbcPersistenceComponent` & `JdbcPersistenceOptions` | DI component (requiring `PersistenceComponent`) registering `PersistenceConfig`, `DataSource`, and `JdbcPersistence`. |

## Installation

Add the dependency in `build.gradle.kts` along with your JDBC driver (and `flyway-database-postgresql` when targeting PostgreSQL):

```kotlin
dependencies {
    implementation("io.kmpkt:kmpkt-persistence-jdbc:0.1.0")
    runtimeOnly("org.xerial:sqlite-jdbc:<version>")
}
```

## DI Setup

Register `JdbcPersistenceComponent` (alongside `PersistenceComponent`) and configure `jdbcPersistence` in `src/di/kotlin`:

```kotlin
import io.kmpkt.di.compiler.Configuration
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.persistence.di.PersistenceComponent
import io.kmpkt.persistence.di.persistence
import io.kmpkt.persistence.jdbc.di.JdbcPersistenceComponent
import io.kmpkt.persistence.jdbc.di.jdbcPersistence

class AppConfiguration : Configuration<App> {
    override fun ContainerBuilder.configure() {
        use<PersistenceComponent>()
        use<JdbcPersistenceComponent>()

        persistence {
            appendLog("events")
            workQueue("outbox")
        }
        jdbcPersistence {
            databaseUrl = "jdbc:sqlite:app.db"
            migrate = true
        }
    }
}
```

## Usage

Create a migrated `DataSource` and construct `JdbcPersistence` in `src/main/kotlin`:

```kotlin
import io.kmpkt.persistence.jdbc.DataSourceFactory
import io.kmpkt.persistence.jdbc.JdbcPersistence
import io.kmpkt.persistence.jdbc.PersistenceConfig

val dataSource = DataSourceFactory.createAndMigrate(PersistenceConfig("jdbc:sqlite:app.db"))
val persistence = JdbcPersistence(dataSource)
```

## Extending

Reuse an existing application `DataSource` by running `PersistenceSchema.migrate` once at startup, which coexists with your own Flyway migrations in `db/migration`:

```kotlin
PersistenceSchema.migrate(existingDataSource)
val persistence = JdbcPersistence(existingDataSource)
```

Disable automatic schema migration in DI when managing Flyway migrations outside the container:

```kotlin
jdbcPersistence {
    databaseUrl = "jdbc:postgresql://localhost:5432/app"
    migrate = false
}
```
