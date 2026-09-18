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
import io.kotest.matchers.string.shouldContain

/**
 * P5: SQLite rendert eine Array-Spalte als `TEXT` und verliert damit ihre
 * Array-Eigenschaft. Der Verlust wird benannt (`W162`) — auf dem
 * Generate-Pfad als Note, auf dem Migrate-Pfad als Diagnose, an jeder
 * Render-Stelle: `CREATE TABLE`, `ADD COLUMN` und der Tabellen-Neubau.
 */
class SqliteArrayDegradationTest : FunSpec({

    val generator = SqliteDdlGenerator()
    val planner = DiffPlanner()
    val diffGen = SqliteDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")
    fun planAndUp(diff: SchemaDiff) =
        diffGen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

    fun arrayTable() = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Identifier(true), ordinal = 1),
            "tags" to ColumnDefinition(NeutralType.Array("text"), ordinal = 2),
        ),
        primaryKey = listOf("id"),
    )

    test("generate: eine Array-Spalte wird TEXT und meldet W162 mit Elementart") {
        val schema = SchemaDefinition(name = "s", version = "1", tables = mapOf("t" to arrayTable()))
        val result = generator.generate(schema)

        result.render() shouldContain "\"tags\" TEXT"
        val note = result.notes.single { it.code == "W162" }
        note.objectName shouldBe "t.tags"
        note.message shouldContain "element type 'text'"
        note.message shouldContain "SQLite has no native"
    }

    // Gegenprobe: eine gewoehnliche Textspalte meldet nichts — sonst haenge
    // die Meldung am Rendern, nicht am Verlust.
    test("generate: eine text-Spalte meldet W162 nicht") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(true), ordinal = 1),
                "label" to ColumnDefinition(NeutralType.Text(40), ordinal = 2),
            ),
            primaryKey = listOf("id"),
        )
        val schema = SchemaDefinition(name = "s", version = "1", tables = mapOf("t" to table))
        generator.generate(schema).notes.none { it.code == "W162" } shouldBe true
    }

    test("migrate: CreateTable meldet W162") {
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("t", arrayTable())))
        val r = planAndUp(diff)
        r.statements.map { it.sql }.first { it.contains("CREATE TABLE") } shouldContain "\"tags\" TEXT"
        r.diagnostics.single { it.code == "W162" }.message shouldContain "element type 'text'"
    }

    test("migrate: ADD COLUMN meldet W162, eine gewoehnliche Spalte nicht") {
        val withArray = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "t", columnsAdded = mapOf("tags" to ColumnDefinition(NeutralType.Array("integer")))),
            ),
        )
        planAndUp(withArray).diagnostics.single { it.code == "W162" }
            .message shouldContain "element type 'integer'"

        val withoutArray = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "t", columnsAdded = mapOf("label" to ColumnDefinition(NeutralType.Text(40)))),
            ),
        )
        planAndUp(withoutArray).diagnostics.none { it.code == "W162" } shouldBe true
    }

    // Der Neubau schreibt die ganze Tabelle neu; ohne die Meldung dort waere
    // gerade der Pfad still, der jede Spalte noch einmal rendert.
    test("migrate: der Tabellen-Neubau meldet W162") {
        fun schema(noteRequired: Boolean) = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(true), ordinal = 1),
                        "tags" to ColumnDefinition(NeutralType.Array("text"), ordinal = 2),
                        "note" to ColumnDefinition(NeutralType.Text(), required = noteRequired, ordinal = 3),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val current = schema(noteRequired = false)
        val desired = schema(noteRequired = true) // Nullbarkeitswechsel auf `note` → Neubau
        val diff = SchemaComparator().compare(current, desired)
        val r = diffGen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        // Beleg, dass der Neubau-Pfad lief: die Tabelle wird neu geschrieben.
        r.statements.map { it.sql }.any { it.contains("CREATE TABLE") } shouldBe true
        r.diagnostics.count { it.code == "W162" } shouldBe 1
    }
})
