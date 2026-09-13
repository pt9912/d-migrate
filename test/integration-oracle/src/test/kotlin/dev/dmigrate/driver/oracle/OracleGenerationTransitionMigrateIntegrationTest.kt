package dev.dmigrate.driver.oracle

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.RawTextAuthorship
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import java.time.Duration
import kotlin.io.path.createTempDirectory

/**
 * Die Generationsuebergaenge, die **kein** Ausdruckswechsel sind — auf Oracle
 * durch die ganze Pipeline, nicht nur im Renderer.
 *
 * **Warum gerade hier.** Oracle ist der einzige der fuenf, dessen Reverse eine
 * Identity in `generation` fuehrt (mit Modus und Sequenznamen); PostgreSQL,
 * MySQL und SQLite falten sie in den Spaltentyp. Ein Soll, das den
 * Identity-Modus aendert oder die Identity entfernt, ist deshalb nur hier aus
 * einem zurueckgelesenen Schema erreichbar — und nur hier live nachweisbar.
 *
 * Das Soll entsteht in jedem Fall **aus dem Ist**, mit genau einem geaenderten
 * Feld. Sonst haenge an demselben Lauf noch eine Typ- oder
 * Nullbarkeitsaenderung, und die Spec belegte nicht, was sie behauptet.
 */
class OracleGenerationTransitionMigrateIntegrationTest : FunSpec({

    val container = OracleContainer(TestImages.ORACLE).withStartupTimeout(Duration.ofMinutes(5))
    lateinit var pool: ConnectionPool

    beforeSpec {
        DatabaseDriverRegistry.register(OracleDriver())
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.ORACLE,
                host = container.host,
                port = container.oraclePort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            ),
        )
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE "counters" (
                        "id" NUMBER(9) PRIMARY KEY,
                        "counter" NUMBER(9) GENERATED ALWAYS AS IDENTITY
                    )
                    """.trimIndent(),
                )
                stmt.execute("""CREATE TABLE "dropper" ("id" NUMBER(9) PRIMARY KEY, "counter" NUMBER(9) GENERATED ALWAYS AS IDENTITY)""")
                stmt.execute(
                    """
                    CREATE TABLE "computed" (
                        "id" NUMBER(9) PRIMARY KEY,
                        "qty" NUMBER(9) NOT NULL,
                        "total" NUMBER GENERATED ALWAYS AS ("qty" * 2) VIRTUAL
                    )
                    """.trimIndent(),
                )
                stmt.execute("""INSERT INTO "computed" ("id", "qty") VALUES (1, 21)""")
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
        DatabaseDriverRegistry.clear()
    }

    fun liveSchema(): SchemaDefinition = OracleSchemaReader().read(pool).schema

    /** Das Ist, mit genau einem geaenderten `generation`-Feld. */
    fun desiredWith(table: String, column: String, generation: ColumnGeneration?): SchemaDefinition {
        val live = liveSchema()
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

    fun migrate(want: SchemaDefinition, changedTable: String? = null): Triple<Int, List<String>, String> {
        val tmp = createTempDirectory("oracle-generation-transition")
        return try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ ->
                    val read = OracleSchemaReader().read(pool)
                    ResolvedSchemaOperand(
                        reference = "live-oracle",
                        schema = read.schema,
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.ORACLE,
                        serverVersion = read.serverVersion,
                    )
                },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, _, serverForm ->
                    SchemaComparator(
                        projection,
                        RawTextAuthorship { _, path, _, _, _ -> path.firstOrNull() == changedTable },
                        serverForm,
                    ).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.ORACLE) OracleDiffDdlGenerator() else null },
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
                    dialect = DatabaseDialect.ORACLE,
                    report = tmp.resolve("report.json"),
                    execute = true,
                    // column-generation-diff-unmapped.md: der Kind-Wechsel-
                    // Spaltentausch ist destructive markiert und braucht
                    // deshalb dasselbe Opt-in wie jede andere destruktive
                    // Operation; die encumbered-Faelle bleiben unabhaengig
                    // davon geblockt.
                    allowDestructive = true,
                ),
            )
            val report = runCatching { java.nio.file.Files.readString(tmp.resolve("report.json")) }.getOrElse { "" }
            Triple(exit, executed, errors.joinToString("; ") + " || REPORT: " + report.take(4000))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** Das Feld-fuer-Feld-Bild, wenn der Post-Compare Drift meldet. */
    fun columnPicture(want: SchemaDefinition, table: String, column: String): String {
        val desired = want.tables.getValue(table).columns.getValue(column)
        val actual = liveSchema().tables[table]?.columns?.get(column)
        return "SOLL type=${desired.type} required=${desired.required} generation=${desired.generation} " +
            "default=${desired.default} || IST type=${actual?.type} required=${actual?.required} " +
            "generation=${actual?.generation} default=${actual?.default}"
    }

    fun identityModeOf(table: String, column: String): String? =
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT generation_type FROM all_tab_identity_cols " +
                        "WHERE table_name = '$table' AND column_name = '$column'",
                ).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    test("the identity mode is switched in place, and the reverse reports the new one") {
        val (exit, executed, errors) = migrate(
            desiredWith("counters", "counter", ColumnGeneration.Identity(IdentityMode.BY_DEFAULT)),
        )

        withClue(errors) { exit shouldBe 0 }
        withClue(executed.joinToString(" | ")) {
            executed.single() shouldContain "MODIFY \"counter\" GENERATED BY DEFAULT AS IDENTITY"
        }
        identityModeOf("counters", "counter") shouldBe "BY DEFAULT"
        // Und der Generator laeuft weiter: ein INSERT ohne Wert bekommt einen.
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("""INSERT INTO "counters" ("id") VALUES (1)""") }
        }
    }

    test("identity is dropped in place; the column keeps its values and loses its generator") {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("""INSERT INTO "dropper" ("id") VALUES (1)""") }
        }

        val want = desiredWith("dropper", "counter", null)
        val (exit, executed, errors) = migrate(want)

        withClue(errors + " || " + columnPicture(want, "dropper", "counter")) { exit shouldBe 0 }
        withClue(executed.joinToString(" | ")) {
            executed[0] shouldContain "MODIFY \"counter\" DROP IDENTITY"
            // Oracle nimmt der Spalte mit der Identity auch das implizite
            // NOT NULL — ohne die zweite Anweisung endete der Lauf in Drift.
            executed[1] shouldContain "NOT NULL"
        }
        identityModeOf("dropper", "counter") shouldBe null
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("""SELECT COUNT(*) FROM "dropper"""").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }

    /**
     * **Die Messung, die die urspruengliche Ablehnung motiviert hat.** Ein
     * `MODIFY (c <typ>)` auf einer virtuellen Spalte wird von Oracle
     * **angenommen** — und aendert nichts: die Spalte ist danach weiter
     * virtuell. Eine Sonde, die nur den Wert liest (42), sieht den
     * Unterschied nicht, denn 42 ist genau das, was der Ausdruck rechnet.
     * Erst der Lauf durch den ganzen Pfad zeigte es, weil der Post-Compare
     * die Spalte weiterhin als berechnet zuruecklas.
     *
     * `column-generation-diff-unmapped.md`: seitdem laeuft der Uebergang
     * ueber den Spaltentausch (`ADD` + `UPDATE`-Kopie + `DROP` + `RENAME`)
     * statt ueber das no-op `MODIFY` — der eingefrorene Wert (42) ueberlebt
     * als gewoehnliche Spalte.
     */
    test("a computed column becomes ordinary via swap, keeping its frozen value") {
        val want = desiredWith("computed", "total", null)
        val (exit, executed, errors) = migrate(want, changedTable = "computed")

        withClue(errors + " || " + columnPicture(want, "computed", "total")) { exit shouldBe 0 }
        withClue(executed.joinToString(" | ")) {
            executed.size shouldBe 5
            executed[0] shouldContain "ADD (\"total__dmg_swap\""
            executed[1] shouldContain "UPDATE"
            executed[2] shouldContain "DROP COLUMN \"total\""
            executed[3] shouldContain "RENAME COLUMN \"total__dmg_swap\" TO \"total\""
            executed[4] shouldContain "MODIFY (\"total\""
        }
        liveSchema().tables.getValue("computed").columns.getValue("total").generation shouldBe null
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("""SELECT "total" FROM "computed" WHERE "id" = 1""").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 42
                }
            }
            // Der Generator ist weg: der Wert laesst sich jetzt frei ueberschreiben.
            conn.createStatement().use { it.execute("""UPDATE "computed" SET "total" = 7 WHERE "id" = 1""") }
        }
    }

    test("an ordinary column becomes computed via swap, with no encumbrance blocking it") {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""CREATE TABLE "plain_calc" ("id" NUMBER(9) PRIMARY KEY, "qty" NUMBER(9) NOT NULL, "total" NUMBER(9))""")
                stmt.execute("""INSERT INTO "plain_calc" ("id", "qty", "total") VALUES (1, 5, 999)""")
            }
        }
        val want = desiredWith(
            "plain_calc", "total",
            ColumnGeneration.Computed("\"qty\" * 2", stored = false),
        )
        val (exit, executed, errors) = migrate(want, changedTable = "plain_calc")

        withClue(errors + " || " + columnPicture(want, "plain_calc", "total")) { exit shouldBe 0 }
        withClue(executed.joinToString(" | ")) {
            executed.size shouldBe 2
            executed[0] shouldContain "DROP COLUMN \"total\""
            executed[1] shouldContain "ADD (\"total\""
        }
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("""SELECT "total" FROM "plain_calc" WHERE "id" = 1""").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 10
                }
            }
        }
    }

    test("an encumbered column (part of the primary key) blocks the swap with a precise reason") {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""CREATE TABLE "keyed_calc" ("id" NUMBER(9) PRIMARY KEY, "qty" NUMBER(9) NOT NULL)""")
                stmt.execute("""INSERT INTO "keyed_calc" ("id", "qty") VALUES (1, 5)""")
            }
        }
        val want = desiredWith(
            "keyed_calc", "id",
            ColumnGeneration.Computed("\"qty\" * 2", stored = false),
        )
        val (exit, executed, errors) = migrate(want, changedTable = "keyed_calc")

        exit shouldBe 8
        executed.shouldBeEmpty()
        withClue(errors) { errors shouldContain "part of the primary key" }
    }
})
