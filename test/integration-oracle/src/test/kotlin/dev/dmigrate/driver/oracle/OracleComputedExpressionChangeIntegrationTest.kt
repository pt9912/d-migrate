package dev.dmigrate.driver.oracle

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.RawTextAuthorship
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
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
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.time.Duration
import kotlin.io.path.createTempDirectory

/**
 * Was Oracle mit einem geaenderten Berechnungsausdruck macht — live gemessen
 * gegen Oracle 23, nicht der Doku entnommen:
 *
 * | Lage | Oracle |
 * | --- | --- |
 * | virtuell, kein Index | `MODIFY` gelingt, der Wert wird neu gerechnet |
 * | virtuell, mit Index | `ORA-54022` |
 * | materialisiert | `ORA-54060`, auch ohne Index |
 *
 * Die beiden Fehlschlaege sind laut — Oracle lehnt ab, statt etwas
 * stillschweigend zu verlieren. Der Renderer blockt sie trotzdem schon beim
 * Planen: ein Lauf, der mitten in der Ausfuehrung scheitert, hat die vorigen
 * Anweisungen bereits angewandt.
 *
 * Die Herkunft wird als Doppel gesetzt: Gegenstand dieser Spec ist das
 * Anwenden, nicht der Herkunftsmechanismus.
 */
class OracleComputedExpressionChangeIntegrationTest : FunSpec({

    val container = OracleContainer(TestImages.ORACLE).withStartupTimeout(Duration.ofMinutes(5))
    lateinit var pool: ConnectionPool

    beforeSpec {
        DatabaseDriverRegistry.register(OracleDriver())
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.ORACLE,
                host = container.host, port = container.oraclePort,
                database = container.databaseName,
                user = container.username, password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    val base = """"quantity" * "unit_price""""

    /** Derselbe Ausdruck, wie Oracle ihn fuehrt: quotiert und ohne Leerraum. */
    val serverForm = """"quantity"*"unit_price""""
    val doubled = """"quantity" * "unit_price" * 2"""
    /**
     * Der Index steht hier in der Form, in der Oracle ihn **zurueckgibt**:
     * ueber einer virtuellen Spalte legt der Server einen Ausdrucks-Index an,
     * und der Reverse liest ihn aus `ALL_IND_EXPRESSIONS` (gemessen). Stuende
     * er hier als Spaltenindex, plante jeder Lauf ihn erneut.
     */
    val indexed = IndexDefinition(
        name = "ix_ocl_total",
        columns = listOf(IndexColumn(name = serverForm, expression = serverForm)),
    )

    fun table(expression: String, stored: Boolean, indices: List<IndexDefinition>) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "quantity" to ColumnDefinition(NeutralType.Integer, required = true),
            "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2), required = true),
            "line_total" to ColumnDefinition(
                NeutralType.Decimal(14, 2),
                generation = ColumnGeneration.Computed(expression, stored = stored),
            ),
        ),
        primaryKey = listOf("id"),
        indices = indices,
    )

    /**
     * Alle drei Tabellen in jedem Soll — sonst plante jeder Lauf die Tabellen
     * der anderen Spezifikationen als `DROP TABLE`, und der Lauf braeche schon
     * daran ab (bestaetigungspflichtig).
     */
    fun schemaWith(changed: String, expression: String) = SchemaDefinition(
        name = "oracle_computed_change", version = "1",
        tables = mapOf(
            "ocl_virtual" to table(
                if (changed == "ocl_virtual") expression else base, stored = false, indices = emptyList(),
            ),
            "ocl_materialized" to table(
                if (changed == "ocl_materialized") expression else base, stored = true, indices = emptyList(),
            ),
            "ocl_indexed" to table(
                if (changed == "ocl_indexed") expression else base, stored = false, indices = listOf(indexed),
            ),
        ),
    )

    /**
     * Ein Lauf; liefert Exit-Code, ausgefuehrte Anweisungen und den Report-Text.
     * [changedTable] ist die Tabelle, fuer die die Herkunft „geaendert" sagt —
     * fuer die uebrigen sagt sie „unveraendert", damit der Vergleich ihre
     * Ausdruecke wegfaltet statt sie mitzuplanen.
     */
    fun migrate(want: SchemaDefinition, changedTable: String?): Triple<Int, List<String>, String> {
        val tmp = createTempDirectory("oracle-computed-change")
        return try {
            val executed = mutableListOf<String>()
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
                printError = { _, _ -> },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.ORACLE,
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

    fun lineTotalFor(table: String, id: Int, quantity: Int, price: String): String? =
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use {
                it.execute("""INSERT INTO "$table" ("id", "quantity", "unit_price") VALUES ($id, $quantity, $price)""")
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("""SELECT "line_total" FROM "$table" WHERE "id" = $id""").use { rs ->
                    if (rs.next()) rs.getBigDecimal(1).toPlainString() else null
                }
            }
        }

    beforeSpec {
        // Der Anfangszustand: drei Tabellen, je eine Form.
        val (exit, _, report) = migrate(schemaWith(changed = "", expression = base), changedTable = null)
        withClue(report) { exit shouldBe 0 }
    }

    fun currentTotal(table: String, id: Int): String? =
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("""SELECT "line_total" FROM "$table" WHERE "id" = $id""").use { rs ->
                    if (rs.next()) rs.getBigDecimal(1).toPlainString() else null
                }
            }
        }

    test("a virtual column without an index is modified in place") {
        lineTotalFor("ocl_virtual", id = 1, quantity = 3, price = "7.00") shouldBe "21"

        val (exit, statements, report) = migrate(
            schemaWith(changed = "ocl_virtual", expression = doubled), changedTable = "ocl_virtual",
        )

        withClue(report) { exit shouldBe 0 }
        withClue(statements.joinToString("; ")) {
            statements.count { it.contains("MODIFY") && it.contains("VIRTUAL") } shouldBe 1
        }
        // Virtuell heisst: bei jedem Zugriff gerechnet — auch die alte Zeile.
        withClue("die bestehende Zeile rechnet nach der neuen Formel") {
            currentTotal("ocl_virtual", id = 1) shouldBe "42"
        }
    }

    test("a materialized column is blocked, naming ORA-54060") {
        val (exit, statements, report) = migrate(
            schemaWith(changed = "ocl_materialized", expression = doubled), changedTable = "ocl_materialized",
        )

        withClue(report) {
            exit shouldBe 8
            report.contains("ORACLE_MATERIALIZED_EXPRESSION_IMMUTABLE") shouldBe true
            report.contains("ORA-54060") shouldBe true
        }
        statements shouldBe emptyList()
    }

    test("an index on the virtual column blocks the change, naming ORA-54022") {
        val (exit, statements, report) = migrate(
            schemaWith(changed = "ocl_indexed", expression = doubled), changedTable = "ocl_indexed",
        )

        withClue(report) {
            exit shouldBe 8
            report.contains("ORACLE_VIRTUAL_EXPRESSION_INDEXED") shouldBe true
            report.contains("ORA-54022") shouldBe true
        }
        statements shouldBe emptyList()
    }
})
