package dev.dmigrate.driver.mysql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.MySQLContainer
import kotlin.io.path.createTempDirectory

/**
 * Ein `default`, den Datei und MySQL-Katalog gleich ausdruecken, ist kein
 * `AlterColumnDefault` (GitHub-Issue #54, Ursache 1).
 *
 * Der Datei-Parser liefert `Int` fuer eine Ganzzahl, der Reader `Long` (`toLongOrNull()`), und fuer
 * `DECIMAL(10,2) DEFAULT 0` liefert MySQL `0.00`, also `Double`; die Gleichheit der data class
 * `NumberLiteral` trennte alle drei. Das Soll entsteht aus dem Lesen der Datenbank, mit dem Default
 * in der Form des Parsers ([fileForm]).
 */
class MysqlDefaultCompareIntegrationTest : FunSpec({

    val container = MySQLContainer(TestImages.MYSQL)
        .withDatabaseName("dmigrate_test").withUsername("dmigrate").withPassword("dmigrate")
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.MYSQL, host = container.host, port = container.firstMappedPort,
                database = container.databaseName, user = container.username, password = container.password,
                params = mapOf("allowPublicKeyRetrieval" to "true"),
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

    fun read(): SchemaDefinition = MysqlSchemaReader().read(pool, SchemaReadOptions()).schema

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
        val tmp = createTempDirectory("mysql-default")
        try {
            val errors = mutableListOf<String>()
            var report = ""
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ ->
                    ResolvedSchemaOperand(
                        reference = "live-mysql", schema = read(), validation = ValidationResult(),
                        dialect = DatabaseDialect.MYSQL,
                    )
                },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MYSQL) MysqlDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
                renderReport = { r, _ -> r.toString().also { report = it } },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}", target = "db:placeholder",
                    dialect = DatabaseDialect.MYSQL, report = tmp.resolve("report.json"), execute = execute,
                ),
            )
            return Outcome(exit, report, errors.joinToString("; "))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // Jede Zeile: Spaltendefinition in der Datenbank und der Default, den die Datei meint.
    val unchanged = listOf(
        Triple("int, Pflicht, 0", "int NOT NULL DEFAULT 0", DefaultValue.NumberLiteral(0)),
        Triple("int, Pflicht, 5", "int NOT NULL DEFAULT 5", DefaultValue.NumberLiteral(5)),
        Triple("int, Pflicht, negativ", "int NOT NULL DEFAULT -1", DefaultValue.NumberLiteral(-1)),
        Triple("int, optional, 0", "int DEFAULT 0", DefaultValue.NumberLiteral(0)),
        Triple("smallint, 5", "smallint NOT NULL DEFAULT 5", DefaultValue.NumberLiteral(5)),
        Triple("decimal(10,2), 0", "decimal(10,2) NOT NULL DEFAULT 0", DefaultValue.NumberLiteral(0)),
        Triple("decimal(10,2), 2.0", "decimal(10,2) NOT NULL DEFAULT 2.0", DefaultValue.NumberLiteral(2.0)),
        Triple("double, 1", "double NOT NULL DEFAULT 1", DefaultValue.NumberLiteral(1)),
        Triple("double, 1.5", "double NOT NULL DEFAULT 1.5", DefaultValue.NumberLiteral(1.5)),
        Triple("tinyint(1), false", "tinyint(1) NOT NULL DEFAULT 0", DefaultValue.BooleanLiteral(false)),
        Triple("varchar", "varchar(20) NOT NULL DEFAULT 'x'", DefaultValue.StringLiteral("x")),
    )
    for ((label, column, default) in unchanged) {
        test("gleicher Default ist keine Aenderung: $label") {
            sql("DROP TABLE IF EXISTS t", "CREATE TABLE t (id int NOT NULL, c $column, PRIMARY KEY (id))")
            val o = migrate(fileForm(read(), default))
            withClue("${o.errors} ${o.report.take(900)}") {
                o.exit shouldBe 0
                o.report.contains("operationsTotal=0") shouldBe true
            }
        }
    }

    // Jede Zeile: eine echte Aenderung des Defaults, sie bleibt eine Operation.
    val changed = listOf(
        Triple("int 0 -> 5", "int NOT NULL DEFAULT 0", DefaultValue.NumberLiteral(5)),
        Triple("int: Default entfernt", "int NOT NULL DEFAULT 0", null),
        Triple("int: Default hinzugefuegt", "int NOT NULL", DefaultValue.NumberLiteral(0)),
        Triple("double 1.5 -> 1", "double NOT NULL DEFAULT 1.5", DefaultValue.NumberLiteral(1)),
        Triple("decimal 0 -> 2.5", "decimal(10,2) NOT NULL DEFAULT 0", DefaultValue.NumberLiteral(2.5)),
    )
    for ((label, column, default) in changed) {
        test("echte Aenderung bleibt eine Operation: $label") {
            sql("DROP TABLE IF EXISTS t", "CREATE TABLE t (id int NOT NULL, c $column, PRIMARY KEY (id))")
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
