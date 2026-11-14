package io.kmpkt.persistence.jdbc

import io.kmpkt.persistence.Persistence
import io.kotest.core.spec.style.scopes.FunSpecContainerScope
import io.kotest.core.spec.style.scopes.FunSpecRootScope
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import javax.sql.DataSource

/**
 * A migrated database on one engine, wired like a real deployment.
 *
 * The SQL in this module claims to be portable across SQLite and PostgreSQL, so the tests run on both rather
 * than taking that on trust - the two disagree about plenty (`ON CONFLICT` support, type affinity, how
 * concurrent writers behave), and only one of them can be covered by a temp file.
 */
internal class TestDatabase(
    val dataSource: DataSource,
) {
    val persistence: Persistence = JdbcPersistence(dataSource)
}

/** One engine the portable SQL has to work on, and how to get a fresh database on it. */
internal class Engine(
    val name: String,
    val available: Boolean,
    private val create: () -> DataSource,
) {
    private val handedOut = mutableListOf<DataSource>()

    fun database(): TestDatabase = TestDatabase(create().also { handedOut += it })

    /**
     * Closes the connection pools handed out so far.
     *
     * Each database is a pool of its own, and a server has a connection limit: without this, a suite of
     * thirty tests runs PostgreSQL out of clients partway through and the failures look like SQL problems
     * rather than leaked pools.
     */
    fun closeDatabases() {
        handedOut.forEach { dataSource -> (dataSource as? AutoCloseable)?.close() }
        handedOut.clear()
    }

    override fun toString(): String = name
}

/**
 * Where the PostgreSQL to test against comes from, in order of preference.
 *
 * `KMPKT_TEST_POSTGRES_URL` first, because Testcontainers is not always able to talk to the Docker that is
 * installed - version 1.21.3 cannot drive Docker 29, whose engine refuses the old API version docker-java
 * asks for, and 1.21.3 is the newest there is. A server started by hand is then the way to run these at all:
 *
 * ```
 * docker run -d --rm --name kmpkt-test-pg -e POSTGRES_PASSWORD=test -e POSTGRES_USER=test \
 *   -e POSTGRES_DB=test -p 55432:5432 postgres:17-alpine
 * export KMPKT_TEST_POSTGRES_URL="jdbc:postgresql://localhost:55432/test?user=test&password=test"
 * ```
 *
 * The URL has to name a database this suite may create databases in, since each [TestDatabase] gets a fresh
 * one so tests do not see each other's rows.
 */
private val configuredPostgresUrl: String? = System.getenv("KMPKT_TEST_POSTGRES_URL")

/** Started lazily, so a run that has a URL or no Docker never touches it. */
private val postgresContainer: PostgreSQLContainer<*> by lazy {
    PostgreSQLContainer("postgres:17-alpine").apply { start() }
}

private var postgresDatabases = 0

/** The administrative URL of whichever PostgreSQL is in play, and the DSN suffix carrying its credentials. */
private fun postgresEndpoint(): Pair<String, String> =
    configuredPostgresUrl?.let { url ->
        val (base, credentials) = url.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }

        base to (if (credentials.isEmpty()) "" else "?$credentials")
    } ?: postgresContainer.let { container ->
        container.jdbcUrl.substringBefore("?") to "?user=${container.username}&password=${container.password}"
    }

internal fun engines(): List<Engine> =
    listOf(
        Engine("sqlite", available = true) {
            val file = Files.createTempFile("kmpkt-test", ".db").also { it.toFile().deleteOnExit() }

            DataSourceFactory.create(PersistenceConfig("jdbc:sqlite:$file")).also(PersistenceSchema::migrate)
        },
        Engine("postgres", available = configuredPostgresUrl != null || dockerIsAvailable()) {
            val (adminUrl, credentials) = postgresEndpoint()
            val name = "kmpkt_test_${postgresDatabases++}"

            java.sql.DriverManager.getConnection(adminUrl + credentials).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DROP DATABASE IF EXISTS $name")
                    statement.executeUpdate("CREATE DATABASE $name")
                }
            }

            DataSourceFactory
                .create(
                    PersistenceConfig(adminUrl.substringBeforeLast("/") + "/" + name + credentials),
                ).also(PersistenceSchema::migrate)
        },
    )

/**
 * Whether a Docker daemon answers. Without one the PostgreSQL engine is skipped rather than failing the
 * build: the portable half still runs on SQLite, and `./gradlew check` has to work on a machine with no
 * Docker - the same reason the Ollama tests are tagged rather than assumed.
 */
private fun dockerIsAvailable(): Boolean =
    runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

/**
 * Runs [body] once per engine, and for an engine that cannot run here leaves a **disabled test behind that
 * says so**.
 *
 * A silently halved suite reports BUILD SUCCESSFUL in a few seconds and looks exactly like a run that covered
 * both engines, which is worse than failing: the portable SQL would go unchecked against the engine that
 * actually ships. A disabled test shows up in the report and in CI with its reason in the name.
 */
internal fun FunSpecRootScope.onEachEngine(body: suspend FunSpecContainerScope.(Engine) -> Unit) {
    engines().forEach { engine ->
        if (engine.available) {
            context(engine.name) {
                afterEach { engine.closeDatabases() }

                body(engine)
            }
        } else {
            test(
                "!${engine.name} engine: skipped - no KMPKT_TEST_POSTGRES_URL and no Docker that Testcontainers" +
                    " can drive, so the portable SQL is unverified against it",
            ) {}
        }
    }
}
