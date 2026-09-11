package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.JdbcMigrationStatementExecutor
import dev.dmigrate.driver.migration.TransactionScope
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * `CREATE INDEX CONCURRENTLY` gegen echtes PostgreSQL.
 *
 * Drei Aussagen, auf denen der Schnitt ruht, und alle drei sind gemessen,
 * nicht abgeschrieben:
 *
 * 1. Die Anweisung ist **in einer offenen Transaktion verboten**
 *    (`CREATE INDEX CONCURRENTLY cannot run inside a transaction block`) —
 *    deshalb `TransactionScope.NO_TRANSACTION`. Laeuft sie hier durch, hat
 *    der Executor den Abschnitt wirklich ausserhalb einer Transaktion
 *    gefahren.
 * 2. Ein **abgebrochener** Lauf hinterlaesst einen Index mit
 *    `pg_index.indisvalid = false`, unter demselben Namen. Ohne Aufraeumen
 *    scheiterte der naechste Lauf daran.
 * 3. `DROP INDEX CONCURRENTLY IF EXISTS` raeumt genau den weg und kostet auf
 *    einem nicht vorhandenen Index nichts.
 */
class PostgresConcurrentIndexIntegrationTest : FunSpec({

    val container = PostgreSQLContainer(TestImages.POSTGRESQL)
        .withDatabaseName("dmigrate_cc")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var pool: ConnectionPool

    fun exec(sql: String) = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { it.execute(sql) }
    }

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(PostgresDriver())
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL,
                host = container.host,
                port = container.firstMappedPort,
                database = "dmigrate_cc",
                user = "dmigrate",
                password = "dmigrate",
                ssl = SslSettings(SslMode.DISABLE),
            ),
        )
        exec("CREATE TABLE orders (id int, bucket int)")
        exec("INSERT INTO orders SELECT g, g % 3 FROM generate_series(1, 1000) g")
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun indexValidity(name: String): Boolean? = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT i.indisvalid FROM pg_class c JOIN pg_index i ON i.indexrelid = c.oid " +
                    "WHERE c.relname = '$name'",
            ).use { rs -> if (rs.next()) rs.getBoolean(1) else null }
        }
    }

    fun addIndexPlan(name: String, unique: Boolean = false) = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = listOf(
            DiffOperation.AddIndex(
                id = "op-1",
                objectRef = DiffObjectRef(DiffObjectType.INDEX, listOf("orders", name)),
                index = IndexDefinition(
                    name = name,
                    columns = listOf(IndexColumn(if (unique) "bucket" else "id")),
                    unique = unique,
                ),
            ),
        ),
    )

    val concurrentOptions = DdlGenerationOptions(
        dialectContext = DdlDialectContext.Postgres(concurrentIndexes = true),
    )

    test("the rendered statements are concurrent and stand outside any transaction") {
        val rendered = PostgresDiffDdlGenerator().generateUp(addIndexPlan("ix_orders_id"), concurrentOptions)

        // Zuerst das Aufraeumen, dann das Anlegen -- ein ungueltiger Rest
        // unter demselben Namen liesse das CREATE sonst scheitern.
        rendered.statements.map { it.sql } shouldBe listOf(
            "DROP INDEX CONCURRENTLY IF EXISTS \"ix_orders_id\";",
            "CREATE INDEX CONCURRENTLY \"ix_orders_id\" ON \"orders\" (\"id\");",
        )
        rendered.statements.map { it.transactionScope } shouldBe
            listOf(TransactionScope.NO_TRANSACTION, TransactionScope.NO_TRANSACTION)
        rendered.diagnostics.single { it.code == "POSTGRES_INDEX_CONCURRENTLY" }
            .message shouldContain "INVALID"
    }

    test("PostgreSQL really accepts them from the executor, and the index ends up valid") {
        val rendered = PostgresDiffDdlGenerator().generateUp(addIndexPlan("ix_orders_run"), concurrentOptions)

        val trace = JdbcMigrationStatementExecutor.execute(pool, rendered.statements)

        withClue("executionError: ${trace.executionError}") { trace.executionCompleted shouldBe true }
        indexValidity("ix_orders_run") shouldBe true
    }

    test("a failed concurrent build leaves an INVALID index — and the next run clears it") {
        // Ein eindeutiger Index auf einer Spalte mit Dubletten scheitert; genau
        // so entsteht der Rest, um den es geht.
        val failing = PostgresDiffDdlGenerator().generateUp(
            addIndexPlan("ix_orders_dup", unique = true), concurrentOptions,
        )
        val failedTrace = JdbcMigrationStatementExecutor.execute(pool, failing.statements)

        failedTrace.executionCompleted shouldBe false
        withClue("der ungueltige Rest ist die Voraussetzung des naechsten Schritts") {
            indexValidity("ix_orders_dup") shouldBe false
        }

        // Derselbe Plan noch einmal: das vorangestellte DROP raeumt den Rest
        // weg, das CREATE scheitert wieder an denselben Dubletten -- aber es
        // scheitert an DENEN und nicht an einem schon vorhandenen Namen.
        val retry = JdbcMigrationStatementExecutor.execute(pool, failing.statements)
        retry.executionError!! shouldContain "duplicate"

        // Nach dem Aufraeumen ist der Name wieder frei.
        exec("DROP INDEX CONCURRENTLY IF EXISTS ix_orders_dup")
        indexValidity("ix_orders_dup") shouldBe null
    }

    test("without the option nothing changes: a plain CREATE INDEX inside the transaction") {
        val rendered = PostgresDiffDdlGenerator().generateUp(addIndexPlan("ix_plain"), DdlGenerationOptions())

        rendered.statements.single().sql shouldBe
            "CREATE INDEX \"ix_plain\" ON \"orders\" (\"id\");"
        rendered.statements.single().transactionScope shouldBe TransactionScope.RUNNER_OWNED
    }
})
