package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.SchemaRollbackRequest
import dev.dmigrate.cli.commands.SchemaRollbackRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.NeutralType
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
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * Ein benannter Typ, den die Datei in ihrer Form schreibt und die Datenbank in der
 * Form des Katalogs liefert, ist **derselbe** Typ (Issue #51).
 *
 * Das Soll entsteht aus dem Lesen der Datenbank, mit den Typen in Datei-Form
 * ([fileForm]): so bleibt der Typ der einzige Unterschied, und eine zusaetzliche
 * Spalte ist die einzige echte Aenderung. Die Datei-Form ist die des Parsers:
 * `fields` leer statt fehlend, kein `ordinal` an Composite-Feldern.
 */
class PostgresCustomTypeFileFormMigrateIntegrationTest : FunSpec({

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

    /** Der Typ so, wie ihn der Datei-Parser liefert. */
    fun fileForm(type: CustomTypeDefinition, description: String? = null): CustomTypeDefinition = type.copy(
        fields = (type.fields ?: emptyMap()).mapValues { (_, f) -> f.copy(ordinal = null) },
        description = description,
    )

    fun withExtraColumn(schema: SchemaDefinition) = schema.copy(
        tables = schema.tables.mapValues { (_, t) ->
            t.copy(columns = LinkedHashMap(t.columns).also { it["extra"] = ColumnDefinition(NeutralType.Text()) })
        },
    )

    data class Outcome(val exit: Int, val report: String, val errors: String)

    fun migrate(want: SchemaDefinition, rollbackOutput: Path? = null): Outcome {
        val tmp = createTempDirectory("pg-ct-file-form")
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
                    rollbackOutput = rollbackOutput, generateRollback = rollbackOutput != null,
                ),
            )
            return Outcome(exit, report, errors.joinToString("; "))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** Wie [migrate], erwartet Exit 0 und genau die zusaetzliche Spalte als einzige Operation. */
    fun migratesOnlyTheColumn(want: SchemaDefinition) {
        val o = migrate(want)
        withClue("${o.errors} ${o.report.take(900)}") {
            o.exit shouldBe 0
            o.report.contains("kind=AlterCustomType") shouldBe false
            o.report.contains("operationsTotal=1") shouldBe true
        }
        // Ein zweiter Lauf ist ohne Operationen.
        val again = migrate(withExtraColumn(read()).let { want })
        withClue("${again.errors} ${again.report.take(900)}") {
            again.exit shouldBe 0
            again.report.contains("operationsTotal=0") shouldBe true
        }
    }

    fun typedTo(read: SchemaDefinition, types: Map<String, CustomTypeDefinition>) = withExtraColumn(read).copy(customTypes = types)

    test("Enum mit zwei Werten in Datei-Form (fields leer statt fehlend)") {
        sql(
            "CREATE TYPE ee AS ENUM ('a', 'b')",
            "CREATE TABLE t (id integer NOT NULL, e ee NOT NULL DEFAULT 'a', PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        migratesOnlyTheColumn(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t) }))
    }

    test("Enum mit drei Werten behaelt die Reihenfolge") {
        sql(
            "CREATE TYPE ee AS ENUM ('c', 'a', 'b')",
            "CREATE TABLE t (id integer NOT NULL, e ee NOT NULL, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        migratesOnlyTheColumn(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t) }))
    }

    test("Composite ohne ordinal an den Feldern") {
        sql(
            "CREATE TYPE pt AS (x integer, y integer)",
            "CREATE TABLE t (id integer NOT NULL, p pt, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        migratesOnlyTheColumn(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t) }))
    }

    test("Domain mit CHECK in Datei-Form") {
        sql(
            "CREATE DOMAIN pos AS integer CHECK (VALUE > 0)",
            "CREATE TABLE t (id integer NOT NULL, n pos, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        migratesOnlyTheColumn(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t) }))
    }

    test("Typ mit description in der Datei") {
        sql(
            "CREATE TYPE ee AS ENUM ('a', 'b')",
            "CREATE TABLE t (id integer NOT NULL, e ee NOT NULL, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        migratesOnlyTheColumn(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t, description = "Zustand") }))
    }

    test("der Rueckweg stellt den Ausgangszustand her") {
        sql(
            "CREATE TYPE ee AS ENUM ('a', 'b')",
            "CREATE TABLE t (id integer NOT NULL, e ee NOT NULL, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        val tmp = createTempDirectory("pg-ct-file-form-rollback")
        try {
            val down = tmp.resolve("down.sql")
            val up = migrate(typedTo(db, db.customTypes.mapValues { (_, t) -> fileForm(t) }), rollbackOutput = down)
            withClue("${up.errors} ${up.report.take(900)}") { up.exit shouldBe 0 }

            val exit = SchemaRollbackRunner(
                dbLoader = { _, _ -> ResolvedSchemaOperand("live-pg", read(), ValidationResult(), dialect = DatabaseDialect.POSTGRESQL) },
                executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            ).execute(
                SchemaRollbackRequest(source = down, target = "db:placeholder", execute = true, allowDestructive = true),
            )
            exit shouldBe 0
            read().tables.getValue("t").columns.keys shouldBe db.tables.getValue("t").columns.keys
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("eine echte Aenderung der Werte blockiert weiter") {
        sql(
            "CREATE TYPE ee AS ENUM ('a', 'b')",
            "CREATE TABLE t (id integer NOT NULL, e ee NOT NULL, PRIMARY KEY (id))",
            pool = pool,
        )
        val db = read()
        val changed = db.customTypes.mapValues { (_, t) -> fileForm(t).copy(values = listOf("a", "b", "c")) }
        val o = migrate(typedTo(db, changed))
        withClue("${o.errors} ${o.report.take(900)}") {
            o.exit shouldBe 8
            o.report.contains("kind=AlterCustomType") shouldBe true
        }
    }
})

private fun sql(vararg statements: String, pool: ConnectionPool) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
    }
}
