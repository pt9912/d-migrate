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
 * Ein `default`, den Datei und PostgreSQL-Katalog gleich ausdruecken, ist kein
 * `AlterColumnDefault` (GitHub-Issue #54, Ursache 1).
 *
 * Der Datei-Parser liefert `Int` fuer eine Ganzzahl, der Reader `Long` (`toLongOrNull()`); die
 * Gleichheit der data class `NumberLiteral` trennte beide. Das Soll entsteht aus dem Lesen der
 * Datenbank, mit dem Default in der Form des Parsers ([fileForm]).
 */
class PostgresDefaultCompareIntegrationTest : FunSpec({

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

    fun sql(vararg statements: String) {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
        }
    }

    fun read(): SchemaDefinition = PostgresSchemaReader().read(pool).schema

    /** Das Soll: die gelesene Datenbank, der Default der Spalte `c` so, wie ihn der Datei-Parser liefert. */
    fun fileForm(db: SchemaDefinition, default: DefaultValue?) = db.copy(
        tables = db.tables.mapValues { (_, t) ->
            val columns = LinkedHashMap(t.columns)
            columns["c"] = columns.getValue("c").copy(default = default)
            t.copy(columns = columns)
        },
    )

    data class Outcome(val exit: Int, val report: String, val errors: String)

    fun migrate(want: SchemaDefinition, execute: Boolean = false): Outcome {
        val tmp = createTempDirectory("pg-default")
        try {
            val errors = mutableListOf<String>()
            var report = ""
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ ->
                    ResolvedSchemaOperand(
                        reference = "live-pg", schema = read(), validation = ValidationResult(),
                        dialect = DatabaseDialect.POSTGRESQL,
                    )
                },
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
                    dialect = DatabaseDialect.POSTGRESQL, report = tmp.resolve("report.json"), execute = execute,
                ),
            )
            return Outcome(exit, report, errors.joinToString("; "))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // Jede Zeile: Spaltendefinition in der Datenbank und der Default, den die Datei meint.
    val unchanged = listOf(
        Triple("integer, Pflicht, 0", "integer NOT NULL DEFAULT 0", DefaultValue.NumberLiteral(0)),
        Triple("integer, Pflicht, 5", "integer NOT NULL DEFAULT 5", DefaultValue.NumberLiteral(5)),
        Triple("integer, optional, 0", "integer DEFAULT 0", DefaultValue.NumberLiteral(0)),
        Triple("smallint, 5", "smallint NOT NULL DEFAULT 5", DefaultValue.NumberLiteral(5)),
        Triple("numeric(10,2), 0", "numeric(10,2) NOT NULL DEFAULT 0", DefaultValue.NumberLiteral(0)),
        Triple("numeric(10,2), 2.0", "numeric(10,2) NOT NULL DEFAULT 2.0", DefaultValue.NumberLiteral(2.0)),
        Triple("double precision, 1", "double precision NOT NULL DEFAULT 1", DefaultValue.NumberLiteral(1)),
        Triple("double precision, 1.5", "double precision NOT NULL DEFAULT 1.5", DefaultValue.NumberLiteral(1.5)),
        Triple("boolean, false", "boolean NOT NULL DEFAULT false", DefaultValue.BooleanLiteral(false)),
        Triple("text", "text NOT NULL DEFAULT 'x'", DefaultValue.StringLiteral("x")),
    )
    for ((label, column, default) in unchanged) {
        test("gleicher Default ist keine Aenderung: $label") {
            sql(
                "DROP SCHEMA public CASCADE", "CREATE SCHEMA public",
                "CREATE TABLE t (id integer NOT NULL, c $column, PRIMARY KEY (id))",
            )
            val o = migrate(fileForm(read(), default))
            withClue("${o.errors} ${o.report.take(900)}") {
                o.exit shouldBe 0
                o.report.contains("operationsTotal=0") shouldBe true
            }
        }
    }

    // Jede Zeile: eine echte Aenderung des Defaults, sie bleibt eine Operation.
    val changed = listOf(
        Triple("integer 0 -> 5", "integer NOT NULL DEFAULT 0", DefaultValue.NumberLiteral(5)),
        Triple("integer: Default entfernt", "integer NOT NULL DEFAULT 0", null),
        Triple("integer: Default hinzugefuegt", "integer NOT NULL", DefaultValue.NumberLiteral(0)),
        Triple("double precision 1.5 -> 1", "double precision NOT NULL DEFAULT 1.5", DefaultValue.NumberLiteral(1)),
        Triple("boolean false -> true", "boolean NOT NULL DEFAULT false", DefaultValue.BooleanLiteral(true)),
    )
    for ((label, column, default) in changed) {
        test("echte Aenderung bleibt eine Operation: $label") {
            sql(
                "DROP SCHEMA public CASCADE", "CREATE SCHEMA public",
                "CREATE TABLE t (id integer NOT NULL, c $column, PRIMARY KEY (id))",
            )
            val o = migrate(fileForm(read(), default), execute = true)
            withClue("${o.errors} ${o.report.take(900)}") {
                o.exit shouldBe 0
                o.report.contains("kind=AlterColumnDefault") shouldBe true
                o.report.contains("operationsTotal=1") shouldBe true
            }
            // Danach ist der Plan leer.
            migrate(fileForm(read(), default)).report.contains("operationsTotal=0") shouldBe true
        }
    }
})
