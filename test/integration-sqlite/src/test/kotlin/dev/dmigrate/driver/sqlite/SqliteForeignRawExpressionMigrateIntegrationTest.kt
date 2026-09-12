package dev.dmigrate.driver.sqlite

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.RawSqlExpressionPortability
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

/**
 * Ein Ausdruck aus einem fremden Dialekt haelt den Migrationslauf an, bevor
 * etwas angewandt ist.
 *
 * Der generate-Pfad laesst solchen Text weg und meldet; `schema migrate
 * --execute` kann das nicht: es schickt die Anweisung sofort an den Server.
 * Der lehnt sie zwar ab — aber erst mitten in einer Folge bereits angewandter
 * Anweisungen. Deshalb blockt der Lauf davor.
 *
 * SQLite ist hier das Ziel, weil sein Diff-Renderer die Spaltenzeile selbst
 * baut (und im Tabellen-Neubau ein zweites Mal) — genau die Stellen, die die
 * Pruefung des generate-Pfads nicht sahen.
 */
class SqliteForeignRawExpressionMigrateIntegrationTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.register(SqliteDriver()) }

    fun newPool(): ConnectionPool = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null, port = null, database = ":memory:", user = null, password = null,
        ),
    )

    fun schemaWith(expression: String?) = SchemaDefinition(
        name = "sqlite_foreign_raw", version = "1",
        tables = mapOf(
            "order_line" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "quantity" to ColumnDefinition(NeutralType.Integer, required = true),
                    "unit_price" to ColumnDefinition(NeutralType.Float(), required = true),
                ).apply {
                    if (expression != null) {
                        this["line_total"] = ColumnDefinition(
                            NeutralType.Float(),
                            generation = ColumnGeneration.Computed(expression, stored = true),
                        )
                    }
                },
                primaryKey = listOf("id"),
            ),
        ),
    )

    /** Ein `schema migrate --execute`; liefert Exit-Code, Anweisungen und Report. */
    fun migrate(pool: ConnectionPool, want: SchemaDefinition): Triple<Int, List<String>, String> {
        val tmp = createTempDirectory("sqlite-foreign-raw")
        return try {
            val executed = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ ->
                    ResolvedSchemaOperand(
                        reference = "live-sqlite",
                        schema = SqliteSchemaReader().read(pool).schema,
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.SQLITE,
                    )
                },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.SQLITE) SqliteDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                // Dasselbe Urteil, das das CLI verdrahtet (`CliRawSqlPortability`,
                // dort einzeln geprueft) — hier direkt, weil dieses Modul nicht
                // am treibenden Adapter haengt.
                rawSqlPortability = { text, dialect ->
                    RawSqlExpressionPortability.assess(text, dialect)
                        .let { if (it.portable) null else it.reason }
                },
                renderReport = { r, _ -> r.toString() },
                printError = { _, _ -> },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.SQLITE,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            val report = runCatching { java.nio.file.Files.readString(tmp.resolve("report.json")) }.getOrElse { "" }
            Triple(exit, executed, report)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun tableExists(pool: ConnectionPool): Boolean = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='order_line'")
                .use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
    }

    test("a PostgreSQL expression blocks the run, and nothing is applied") {
        val pool = newPool()
        try {
            val (exit, executed, report) = migrate(pool, schemaWith("(quantity)::numeric * unit_price"))

            withClue(report) {
                exit shouldBe 8
                report.contains("E053") shouldBe true
                report.contains("computed expression of 'line_total'") shouldBe true
            }
            withClue(executed.joinToString("; ")) { executed shouldBe emptyList() }
            withClue("die Tabelle darf nicht halb entstanden sein") { tableExists(pool) shouldBe false }
        } finally {
            pool.close()
        }
    }

    test("an expression SQLite can parse goes through") {
        val pool = newPool()
        try {
            val (exit, _, report) = migrate(pool, schemaWith("quantity * unit_price"))

            withClue(report) { exit shouldBe 0 }
            tableExists(pool) shouldBe true
        } finally {
            pool.close()
        }
    }
})
