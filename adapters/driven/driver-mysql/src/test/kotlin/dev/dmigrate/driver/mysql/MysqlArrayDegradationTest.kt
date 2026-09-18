package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedTable
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
import io.kotest.matchers.string.shouldNotContain

/**
 * P5: MySQL rendert eine Array-Spalte als `JSON` und verliert damit ihre
 * Array-Eigenschaft. Der Verlust wird benannt (`W162`) — auf dem
 * Generate-Pfad als Note, auf dem Migrate-Pfad als Diagnose, mit demselben
 * Wortlaut.
 */
class MysqlArrayDegradationTest : FunSpec({

    val generator = MysqlDdlGenerator()
    val planner = DiffPlanner()
    val diffGen = MysqlDiffDdlGenerator()

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

    test("generate: eine Array-Spalte wird JSON und meldet W162 mit Elementart") {
        val schema = SchemaDefinition(name = "s", version = "1", tables = mapOf("t" to arrayTable()))
        val result = generator.generate(schema)

        result.render() shouldContain "JSON"
        val note = result.notes.single { it.code == "W162" }
        note.objectName shouldBe "t.tags"
        note.message shouldContain "element type 'text'"
        note.message shouldContain "MySQL has no native"
        note.hint!! shouldContain "JSON array"
    }

    // Gegenprobe: kein Array, keine Note. Sonst meldete jede Spalte den
    // Verlust, und die Meldung sagte nichts mehr.
    test("generate: eine json-Spalte meldet W162 nicht") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(true), ordinal = 1),
                "payload" to ColumnDefinition(NeutralType.Json, ordinal = 2),
                "label" to ColumnDefinition(NeutralType.Text(40), ordinal = 3),
            ),
            primaryKey = listOf("id"),
        )
        val schema = SchemaDefinition(name = "s", version = "1", tables = mapOf("t" to table))
        generator.generate(schema).notes.none { it.code == "W162" } shouldBe true
    }

    test("migrate: CreateTable meldet W162") {
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("t", arrayTable())))
        val r = planAndUp(diff)
        r.statements.map { it.sql }.first { it.contains("CREATE TABLE") } shouldContain "JSON"
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

    // B2, die Grenze: was MySQL aus einem Array macht, kann kein Reader
    // zurueckgewinnen. Der Hinweg ist meldbar, der Rueckweg nicht — das ist
    // der **erwartete** Ausgang, kein Defekt.
    test("die Kette array(integer) -> JSON -> Reverse -> json ist der erwartete Ausgang") {
        val rendered = MysqlTypeMapper().toSql(NeutralType.Array("integer"))
        rendered shouldBe "JSON"

        val back = MysqlTypeMapping.mapColumn(
            MysqlTypeMapping.ColumnInput(
                dataType = rendered.lowercase(),
                columnType = rendered.lowercase(),
                isAutoIncrement = false,
                charMaxLen = null,
                numPrecision = null,
                numScale = null,
                tableName = "t",
                colName = "tags",
            ),
        )
        back.type shouldBe NeutralType.Json
        // Und nicht etwa ein Array mit verlorener Elementart: die
        // Array-Eigenschaft ist weg, nicht bloss ihr Inhalt.
        back.type.toString() shouldNotContain "Array"
    }
})
