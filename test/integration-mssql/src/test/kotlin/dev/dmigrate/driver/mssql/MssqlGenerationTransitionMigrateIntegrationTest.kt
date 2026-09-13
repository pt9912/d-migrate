package dev.dmigrate.driver.mssql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory

/**
 * `column-generation-diff-unmapped.md`: der Kind-Wechsel (gewoehnlich ↔
 * berechnet) auf SQL Server, durch die ganze Migrationspipeline. T-SQL kennt
 * keinen In-Place-Zweig (`ALTER COLUMN … AS (…)` ist ein Syntaxfehler,
 * gemessen gegen 2025); seitdem laufen beide Richtungen ueber den
 * Spaltentausch. Vorher blockte diese Operation unbedingt — es gab keine
 * eigene Integrationsspec dafuer (die "blockt"-Zeile in
 * `column-generation-diff-unmapped.md` war die Abwesenheit eines
 * Renderer-Zweigs, kein Live-Verhalten).
 */
class MssqlGenerationTransitionMigrateIntegrationTest : FunSpec({

    val container = startMssqlContainer()

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = poolFor(container, "dmigrate_gen_transition")
        execDdl(
            pool,
            "CREATE TABLE computed (id INT NOT NULL PRIMARY KEY, qty INT NOT NULL, " +
                "total AS (qty * 2) PERSISTED)",
            "INSERT INTO computed (id, qty) VALUES (1, 21)",
            "CREATE TABLE plain_calc (id INT NOT NULL PRIMARY KEY, qty INT NOT NULL, total INT)",
            "INSERT INTO plain_calc (id, qty, total) VALUES (1, 5, 999)",
            "CREATE TABLE keyed_calc (id INT NOT NULL PRIMARY KEY, qty INT NOT NULL)",
            "INSERT INTO keyed_calc (id, qty) VALUES (1, 5)",
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun desiredWith(table: String, column: String, generation: ColumnGeneration?): SchemaDefinition {
        val live = readSchema(pool)
        val definition = live.tables.getValue(table)
        val column0 = definition.columns.getValue(column)
        return live.copy(
            tables = live.tables + (
                table to definition.copy(
                    columns = LinkedHashMap(definition.columns).also {
                        it[column] = column0.copy(generation = generation)
                    },
                )
                ),
        )
    }

    fun migrate(want: SchemaDefinition): Triple<Int, List<String>, String> {
        val tmp = createTempDirectory("mssql-generation-transition")
        return try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize, _, _ ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                    allowDestructive = true,
                ),
            )
            Triple(exit, executed, errors.joinToString("; "))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("a computed column becomes ordinary via swap, keeping its frozen value") {
        val want = desiredWith("computed", "total", null)
        val (exit, executed, errors) = migrate(want)

        withClue(errors) { exit shouldBe 0 }
        withClue(executed.joinToString(" | ")) {
            executed.size shouldBe 7
            executed[0] shouldContain "ADD [total__dmg_swap]"
            executed[3] shouldContain "DROP COLUMN [total]"
            executed[4] shouldContain "sp_rename"
            executed[6] shouldContain "ALTER COLUMN [total]"
        }
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT total FROM computed WHERE id = 1").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 42
                }
            }
            conn.createStatement().use { it.execute("UPDATE computed SET total = 7 WHERE id = 1") }
        }
    }

    test("an ordinary column becomes computed via swap, with no encumbrance blocking it") {
        val want = desiredWith("plain_calc", "total", ColumnGeneration.Computed("qty * 2", stored = true))
        val (exit, executed, errors) = migrate(want)

        withClue(errors) { exit shouldBe 0 }
        withClue(executed.joinToString(" | ")) {
            executed.size shouldBe 3
            executed[1] shouldContain "DROP COLUMN [total]"
            executed[2] shouldContain "ADD [total]"
        }
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT total FROM plain_calc WHERE id = 1").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 10
                }
            }
        }
    }

    test("an encumbered column (part of the primary key) blocks the swap with a precise reason") {
        val want = desiredWith("keyed_calc", "id", ColumnGeneration.Computed("qty * 2", stored = true))
        val (exit, executed, _) = migrate(want)

        exit shouldBe 8
        executed.shouldBeEmpty()
    }
})
