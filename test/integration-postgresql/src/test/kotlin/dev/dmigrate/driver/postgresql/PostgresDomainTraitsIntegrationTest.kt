package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.io.path.createTempDirectory

/**
 * `DEFAULT`, `NOT NULL` und Kollation einer Domain gegen eine echte PostgreSQL-Datenbank (Issue #59).
 *
 * Der Reader liest sie aus dem Katalog; `required` einer Domain-Spalte kommt aus `attnotnull`, nicht aus
 * `is_nullable` (das die Domain einrechnet). Eine Aenderung an der Domain ist sichtbar und blockiert
 * (Exit 8), statt als falsche Spaltenoperation zu erscheinen.
 */
class PostgresDomainTraitsIntegrationTest : FunSpec({

    val container = PostgreSQLContainer(TestImages.POSTGRESQL)
    lateinit var pool: ConnectionPool

    beforeSpec {
        DatabaseDriverRegistry.register(PostgresDriver())
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL, host = container.host, port = container.firstMappedPort,
                database = container.databaseName, user = container.username, password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    beforeEach {
        sql("DROP SCHEMA public CASCADE", "CREATE SCHEMA public", pool = pool)
    }

    fun read(): SchemaDefinition = PostgresSchemaReader().read(pool).schema

    fun createDomains() = sql(
        "CREATE DOMAIN d_def AS integer DEFAULT 5 CHECK (VALUE > 0)",
        "CREATE DOMAIN d_nn AS integer NOT NULL DEFAULT 1",
        "CREATE DOMAIN d_col AS text COLLATE \"C\"",
        "CREATE DOMAIN d_all AS text COLLATE \"C\" NOT NULL DEFAULT 'x' CHECK (length(VALUE) > 0)",
        "CREATE DOMAIN d_plain AS integer",
        """CREATE TABLE t (
            id integer PRIMARY KEY, a d_def, b d_nn, c d_col, f d_all, g d_plain, own integer NOT NULL
        )""",
        pool = pool,
    )

    data class Outcome(val exit: Int, val report: String, val errors: String)

    fun migrate(want: SchemaDefinition): Outcome {
        val tmp = createTempDirectory("pg-domain-traits")
        try {
            val errors = mutableListOf<String>()
            var report = ""
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ -> ResolvedSchemaOperand(
                    reference = "live-pg", schema = read(), validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                ) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
                renderReport = { r, _ -> r.toString().also { report = it } },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}", target = "db:placeholder",
                    dialect = DatabaseDialect.POSTGRESQL, report = tmp.resolve("report.json"), execute = true,
                ),
            )
            return Outcome(exit, report, errors.joinToString("; "))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("der Reader liest DEFAULT, NOT NULL und Kollation einer Domain") {
        createDomains()
        val types = read().customTypes

        types.getValue("d_def").default shouldBe DefaultValue.NumberLiteral(5L)
        types.getValue("d_def").notNull shouldBe false
        types.getValue("d_def").check shouldBe "VALUE > 0"
        types.getValue("d_nn").notNull shouldBe true
        types.getValue("d_col").collation shouldBe "C"
        types.getValue("d_col").notNull shouldBe false
        with(types.getValue("d_all")) {
            default shouldBe DefaultValue.StringLiteral("x")
            notNull shouldBe true
            collation shouldBe "C"
        }
        with(types.getValue("d_plain")) {
            default shouldBe null
            notNull shouldBe false
            collation shouldBe null
        }
    }

    test("required einer Domain-Spalte kommt aus der Spalte, nicht aus der Domain") {
        createDomains()
        val columns = read().tables.getValue("t").columns

        columns.getValue("b").required shouldBe false // d_nn NOT NULL gehoert der Domain
        columns.getValue("f").required shouldBe false // d_all NOT NULL
        columns.getValue("g").required shouldBe false
        columns.getValue("own").required shouldBe true // eigenes NOT NULL
    }

    test("schema generate einer gelesenen Domain behaelt alle drei Eigenschaften") {
        createDomains()
        val ddl = PostgresDdlGenerator().generate(read()).render()
        ddl.contains("""CREATE DOMAIN "d_all" AS TEXT COLLATE "C" DEFAULT 'x' NOT NULL CHECK (length(VALUE) > 0);""") shouldBe true
        ddl.contains("""CREATE DOMAIN "d_def" AS INTEGER DEFAULT 5 CHECK (VALUE > 0);""") shouldBe true

        // Zurueck in eine leere Datenbank gespielt, liest sich dasselbe wieder.
        val before = read()
        sql("DROP SCHEMA public CASCADE", "CREATE SCHEMA public", pool = pool)
        val statements = PostgresDdlGenerator().generate(before).statements.map { it.sql }
        sql(*statements.toTypedArray(), pool = pool)
        val after = read()
        withClue("customTypes") {
            SchemaComparator().compare(before, after).customTypesChanged.isEmpty() shouldBe true
        }
        after.customTypes.getValue("d_all").notNull shouldBe true
        after.tables.getValue("t").columns.getValue("b").required shouldBe false
    }

    test("eine unveraenderte Datenbank plant nichts") {
        createDomains()
        val want = read()
        val o = migrate(want)
        withClue("${o.errors} ${o.report.take(900)}") {
            o.exit shouldBe 0
            o.report.contains("operationsTotal=0") shouldBe true
        }
    }

    test("ALTER DOMAIN SET NOT NULL ist eine Aenderung der Domain, keine Spaltenoperation") {
        createDomains()
        val want = read()
        sql("ALTER DOMAIN d_plain SET NOT NULL", pool = pool)
        val o = migrate(want)
        withClue("${o.errors} ${o.report.take(1200)}") {
            o.report.contains("kind=AlterCustomType") shouldBe true
            o.report.contains("AlterColumnNullability") shouldBe false
            o.exit shouldBe 8
        }
    }

    test("ALTER DOMAIN DROP NOT NULL, SET DEFAULT und DROP DEFAULT sind sichtbar") {
        createDomains()
        val want = read()
        sql(
            "ALTER DOMAIN d_nn DROP NOT NULL",
            "ALTER DOMAIN d_def SET DEFAULT 9",
            "ALTER DOMAIN d_all DROP DEFAULT",
            pool = pool,
        )
        val o = migrate(want)
        withClue("${o.errors} ${o.report.take(1200)}") {
            o.report.contains("AlterColumnNullability") shouldBe false
            o.report.contains("AlterColumnDefault") shouldBe false
            o.exit shouldBe 8
        }
    }
})

private fun sql(vararg statements: String, pool: ConnectionPool) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
    }
}
