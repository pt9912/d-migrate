package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.PostgresServerVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Eine berechnete Spalte wird auf PostgreSQL in beiden Pfaden gleich
 * geschrieben — `schema generate` und der Migrationspfad.
 *
 * Welche Speicherform dahintersteht, haengt bei PostgreSQL als einzigem
 * Dialekt an der **Version** (gemessen an 18.6): bis 17 ist `VIRTUAL` ein
 * Syntaxfehler und `STORED` Pflichtwort, ab 18 ist `VIRTUAL` gueltig und sogar
 * die Vorgabe. Ist die Zielversion unbekannt — ein Dateiziel hat keine —, gilt
 * die aktuellste gemessene.
 *
 * Und die Spalte traegt weder `NOT NULL` noch `DEFAULT`: ihren Wert bestimmt
 * der Ausdruck.
 */
class PostgresComputedColumnRenderTest : FunSpec({

    fun tableWith(generation: ColumnGeneration) = TableDefinition(
        columns = linkedMapOf(
            "quantity" to ColumnDefinition(NeutralType.Integer, required = true),
            "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2), required = true),
            "line_total" to ColumnDefinition(NeutralType.Decimal(14, 2), generation = generation),
        ),
    )

    val computed = ColumnGeneration.Computed("quantity * unit_price", stored = true)

    test("generate renders the column with its expression") {
        val schema = SchemaDefinition(name = "App", version = "1", tables = mapOf("order_line" to tableWith(computed)))

        val sql = PostgresDdlGenerator().generate(schema, DdlGenerationOptions()).statements.map { it.sql }
            .first { it.contains("CREATE TABLE") }

        sql shouldContain "\"line_total\" DECIMAL(14,2) GENERATED ALWAYS AS (quantity * unit_price) STORED"
    }

    test("the migrate path renders the same line") {
        val planner = DiffPlanner()
        val empty = SchemaDefinition(name = "App", version = "1")
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("order_line", tableWith(computed))))

        val sql = PostgresDiffDdlGenerator()
            .generateUp(planner.plan(empty, empty, diff), DdlGenerationOptions()).statements.map { it.sql }
            .first { it.contains("CREATE TABLE") }

        sql shouldContain "\"line_total\" DECIMAL(14,2) GENERATED ALWAYS AS (quantity * unit_price) STORED"
    }

    test("without a known target version the virtual form is rendered — the newest measured wins") {
        val schema = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("order_line" to tableWith(ColumnGeneration.Computed("quantity * unit_price"))),
        )

        val result = PostgresDdlGenerator().generate(schema, DdlGenerationOptions())
        val sql = result.statements.map { it.sql }.first { it.contains("CREATE TABLE") }

        sql shouldContain "GENERATED ALWAYS AS (quantity * unit_price) VIRTUAL"
        // Nichts wird degradiert, also gibt es auch nichts zu melden.
        result.notes.none { it.code == PostgresComputedStorage.DEGRADED_TO_STORED } shouldBe true
    }

    test("against a server below 18 it degrades to STORED — and says so") {
        val schema = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("order_line" to tableWith(ColumnGeneration.Computed("quantity * unit_price"))),
        )
        val options = DdlGenerationOptions(
            dialectContext = DdlDialectContext.Postgres(serverVersion = PostgresServerVersion(16, 4)),
        )

        val result = PostgresDdlGenerator().generate(schema, options)
        val sql = result.statements.map { it.sql }.first { it.contains("CREATE TABLE") }

        sql shouldContain "GENERATED ALWAYS AS (quantity * unit_price) STORED"
        sql shouldNotContain "VIRTUAL"
        val note = result.notes.single { it.code == PostgresComputedStorage.DEGRADED_TO_STORED }
        note.objectName shouldBe "order_line.line_total"
        note.message shouldContain "16.4"
    }

    test("against a server at 18 or later the virtual form stands") {
        val schema = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("order_line" to tableWith(ColumnGeneration.Computed("quantity * unit_price"))),
        )
        val options = DdlGenerationOptions(
            dialectContext = DdlDialectContext.Postgres(serverVersion = PostgresServerVersion(18, 6)),
        )

        val result = PostgresDdlGenerator().generate(schema, options)

        result.statements.map { it.sql }.first { it.contains("CREATE TABLE") } shouldContain
            "GENERATED ALWAYS AS (quantity * unit_price) VIRTUAL"
        result.notes.none { it.code == PostgresComputedStorage.DEGRADED_TO_STORED } shouldBe true
    }

    test("the migrate path degrades and warns the same way") {
        val planner = DiffPlanner()
        val empty = SchemaDefinition(name = "App", version = "1")
        val table = tableWith(ColumnGeneration.Computed("quantity * unit_price"))
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("order_line", table)))
        val options = DdlGenerationOptions(
            dialectContext = DdlDialectContext.Postgres(serverVersion = PostgresServerVersion(16, 4)),
        )

        val result = PostgresDiffDdlGenerator().generateUp(planner.plan(empty, empty, diff), options)

        result.statements.map { it.sql }.first { it.contains("CREATE TABLE") } shouldContain
            "GENERATED ALWAYS AS (quantity * unit_price) STORED"
        result.diagnostics.single { it.code == PostgresComputedStorage.DEGRADED_TO_STORED }
            .message shouldContain "16.4"
    }

    test("a computed column carries neither NOT NULL nor DEFAULT nor UNIQUE") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "quantity" to ColumnDefinition(NeutralType.Integer),
                "line_total" to ColumnDefinition(
                    NeutralType.Decimal(14, 2),
                    required = true,
                    unique = true,
                    generation = computed,
                ),
            ),
        )
        val schema = SchemaDefinition(name = "App", version = "1", tables = mapOf("order_line" to table))

        val line = PostgresDdlGenerator().generate(schema, DdlGenerationOptions()).statements.map { it.sql }
            .first { it.contains("CREATE TABLE") }
            .lines().first { it.contains("line_total") }

        line shouldContain "GENERATED ALWAYS AS"
        line.contains("NOT NULL") shouldBe false
        line.contains("UNIQUE") shouldBe false
    }

    test("an added computed column carries the clause through ALTER TABLE") {
        val planner = DiffPlanner()
        val empty = SchemaDefinition(name = "App", version = "1")
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "order_line",
                    columnsAdded = mapOf("line_total" to ColumnDefinition(NeutralType.Decimal(14, 2), generation = computed)),
                ),
            ),
        )

        val sql = PostgresDiffDdlGenerator()
            .generateUp(planner.plan(empty, empty, diff), DdlGenerationOptions()).statements.map { it.sql }
            .first { it.contains("ADD COLUMN") }

        sql shouldContain "GENERATED ALWAYS AS (quantity * unit_price) STORED"
    }
})
