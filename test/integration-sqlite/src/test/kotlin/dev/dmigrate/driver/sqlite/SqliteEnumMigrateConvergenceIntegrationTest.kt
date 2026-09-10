package dev.dmigrate.driver.sqlite

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory

/**
 * Eine Enum-Spalte laesst `schema migrate` gegen SQLite konvergieren, und ihr
 * Wertevorrat wird durchgesetzt.
 *
 * SQLite hat keinen Enum-Typ; der Vorrat lebt als `CHECK` an der Textspalte.
 * Rendert der Migrationspfad ihn nicht, traegt das Ziel den Vorrat nirgends —
 * und der Vergleich meldet nach jedem Lauf Drift, obwohl der Lauf getan hat,
 * was verlangt war.
 *
 * SQLite kennt kein `ALTER COLUMN`: eine geaenderte Werteliste laeuft ueber den
 * Tabellen-Neubau, der die ganze Tabelle neu schreibt. Genau dort darf der
 * CHECK nicht verlorengehen.
 */
class SqliteEnumMigrateConvergenceIntegrationTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.register(SqliteDriver()) }

    fun newPool(): ConnectionPool = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null, port = null, database = ":memory:", user = null, password = null,
        ),
    )

    fun schemaWith(values: List<String>) = SchemaDefinition(
        name = "sqlite_enum", version = "1",
        tables = mapOf("mood_probe" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer),
                "mood" to ColumnDefinition(NeutralType.Enum(values = values)),
            ),
            primaryKey = listOf("id"),
        )),
    )

    /** Ein `schema migrate --execute`-Lauf; liefert Exit-Code und Anweisungszahl. */
    fun migrate(pool: ConnectionPool, want: SchemaDefinition): Pair<Int, Int> {
        val tmp = createTempDirectory("sqlite-enum-convergence")
        try {
            var statements = 0
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ -> ResolvedSchemaOperand(
                    reference = "live-sqlite",
                    schema = SqliteSchemaReader().read(pool).schema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.SQLITE,
                ) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.SQLITE) SqliteDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    statements += stmts.size
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
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
            withClue(errors.joinToString("; ") + " || " + report) { exit shouldBe 0 }
            return exit to statements
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun tableSql(pool: ConnectionPool): String = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='mood_probe'")
                .use { rs -> if (rs.next()) rs.getString(1) else "" }
        }
    }

    test("a second migrate against a converged enum column plans nothing and keeps the CHECK") {
        val pool = newPool()
        try {
            migrate(pool, schemaWith(listOf("red", "green")))
            withClue(tableSql(pool)) { tableSql(pool) shouldContain "CHECK (\"mood\" IN ('red', 'green'))" }

            val (_, statements) = migrate(pool, schemaWith(listOf("red", "green")))

            statements shouldBe 0
            withClue(tableSql(pool)) { tableSql(pool) shouldContain "CHECK (\"mood\" IN ('red', 'green'))" }
        } finally {
            pool.close()
        }
    }

    test("a changed value vocabulary survives the table rebuild") {
        val pool = newPool()
        try {
            migrate(pool, schemaWith(listOf("red", "green")))

            val (_, statements) = migrate(pool, schemaWith(listOf("red", "brown")))

            withClue("die Werteliste aendert sich, also gibt es etwas zu tun") { (statements > 0) shouldBe true }
            val sql = tableSql(pool)
            withClue(sql) {
                sql shouldContain "CHECK (\"mood\" IN ('red', 'brown'))"
                sql.contains("'green'") shouldBe false
            }
            // Und danach ist wieder Ruhe.
            migrate(pool, schemaWith(listOf("red", "brown"))).second shouldBe 0
        } finally {
            pool.close()
        }
    }

    test("the database really refuses a value outside the vocabulary") {
        val pool = newPool()
        try {
            migrate(pool, schemaWith(listOf("red", "green")))
            val rejected = runCatching {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { it.execute("INSERT INTO mood_probe (id, mood) VALUES (1, 'blue')") }
                }
            }.isFailure

            withClue("ohne diese Zusicherung waere der CHECK nur Text im Katalog") { rejected shouldBe true }
        } finally {
            pool.close()
        }
    }
})
