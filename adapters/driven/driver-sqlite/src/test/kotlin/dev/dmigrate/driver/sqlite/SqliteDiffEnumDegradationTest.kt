package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * SQLite hat keinen Enum-Typ: der Wertevorrat wird als `CHECK` an der
 * Textspalte durchgesetzt — an jeder Render-Stelle gleich, auch im
 * Tabellen-Neubau, der die ganze Tabelle neu schreibt. `W134` bleibt fuer den
 * Fall, in dem es nichts durchzusetzen gibt.
 */
class SqliteDiffEnumDegradationTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")
    fun planAndUp(diff: SchemaDiff) =
        gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

    test("CreateTable renders the enum column with the CHECK that enforces its values") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "status" to ColumnDefinition(NeutralType.Enum(values = listOf("open", "closed"))),
            ),
            primaryKey = listOf("id"),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("tickets", table)))
        val r = planAndUp(diff)
        val createTable = r.statements.map { it.sql }.first { it.contains("CREATE TABLE") }
        createTable shouldContainStr "\"status\" TEXT"
        createTable shouldContainStr "CHECK (\"status\" IN ('open', 'closed'))"
        r.diagnostics.any { it.code == "W134" } shouldBe false
    }

    test("ADD COLUMN carries the same CHECK") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "tickets",
                    columnsAdded = mapOf("prio" to ColumnDefinition(NeutralType.Enum(values = listOf("lo", "hi")))),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.statements.map { it.sql }.first { it.contains("ADD COLUMN") } shouldContainStr
            "CHECK (\"prio\" IN ('lo', 'hi'))"
        r.diagnostics.any { it.code == "W134" } shouldBe false
    }

    test("an enum without values keeps the warning — there is nothing to enforce") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "tickets",
                    columnsAdded = mapOf("prio" to ColumnDefinition(NeutralType.Enum())),
                ),
            ),
        )
        planAndUp(diff).diagnostics.any { it.code == "W134" } shouldBe true
    }

    // Ein Neubau schreibt die ganze Tabelle neu (SQLite kennt kein
    // ALTER COLUMN fuer die Nullbarkeit). Die Enum-Spalte muss dabei ihren
    // CHECK behalten — sonst verlaere gerade der Neubau die Durchsetzung.
    test("the table rebuild keeps the enum column's CHECK") {
        fun schema(noteRequired: Boolean) = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf(
                "tickets" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                        "status" to ColumnDefinition(NeutralType.Enum(values = listOf("open", "closed"))),
                        "note" to ColumnDefinition(NeutralType.Text(), required = noteRequired),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val current = schema(noteRequired = false)
        val desired = schema(noteRequired = true) // nullability change on `note` → rebuild
        val diff = SchemaComparator().compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        // sanity: the change really went through the rebuild path (temp-table recreate)
        val createTable = r.statements.map { it.sql }.first { it.contains("CREATE TABLE") }
        createTable shouldContainStr "CHECK (\"status\" IN ('open', 'closed'))"
        r.diagnostics.any { it.code == "W134" } shouldBe false
    }
})
