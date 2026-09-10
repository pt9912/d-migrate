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
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Eine berechnete Spalte wird auf PostgreSQL in beiden Pfaden gleich
 * geschrieben — `schema generate` und der Migrationspfad.
 *
 * `STORED` steht dort immer: PostgreSQL kennt keine virtuelle Form, `VIRTUAL`
 * ist ein Syntaxfehler (live gemessen). Und die Spalte traegt weder `NOT NULL`
 * noch `DEFAULT`: ihren Wert bestimmt der Ausdruck.
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

    test("a virtual computation still renders as STORED — PostgreSQL has no other form") {
        val schema = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("order_line" to tableWith(ColumnGeneration.Computed("quantity * unit_price"))),
        )

        val sql = PostgresDdlGenerator().generate(schema, DdlGenerationOptions()).statements.map { it.sql }
            .first { it.contains("CREATE TABLE") }

        sql shouldContain "GENERATED ALWAYS AS (quantity * unit_price) STORED"
        sql shouldNotContain "VIRTUAL"
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
