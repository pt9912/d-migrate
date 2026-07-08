package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedCustomType
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Enum-Degradations-Slice (Option 2a + W134), PostgreSQL migrate/diff path:
 *  - a `refType` enum references its native type (CreateCustomType → CREATE TYPE),
 *    ordered types-before-tables (Review F4), and does NOT warn — faithful (AP2);
 *  - an inline-`values` enum still degrades to bare TEXT but is now LOUD via W134
 *    (AP3), at both CreateTable and AddColumn (Review F1).
 */
class PostgresDiffTableOpsEnumTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")
    fun planAndUp(diff: SchemaDiff) =
        gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

    val moodType = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("happy", "sad"))

    test("refType enum → native type reference + CREATE TYPE, ordered types-before-table, no W134") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "mood_col" to ColumnDefinition(NeutralType.Enum(refType = "mood")),
            ),
            primaryKey = listOf("id"),
        )
        val diff = SchemaDiff(
            customTypesAdded = listOf(NamedCustomType("mood", moodType)),
            tablesAdded = listOf(NamedTable("people", table)),
        )
        val sqls = planAndUp(diff).statements.map { it.sql }
        val createType = sqls.indexOfFirst { it.contains("CREATE TYPE \"mood\" AS ENUM") }
        val createTable = sqls.indexOfFirst { it.contains("CREATE TABLE \"people\"") }
        (createType >= 0) shouldBe true
        (createTable >= 0) shouldBe true
        (createType < createTable) shouldBe true // F4: TYPES phase precedes TABLES
        sqls[createTable] shouldContainStr "\"mood_col\" \"mood\"" // native type reference, not TEXT
        planAndUp(diff).diagnostics.any { it.code == "W134" } shouldBe false
    }

    test("inline-values enum → bare TEXT but LOUD via W134 (CreateTable)") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "status" to ColumnDefinition(NeutralType.Enum(values = listOf("open", "closed"))),
            ),
            primaryKey = listOf("id"),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("tickets", table)))
        val r = planAndUp(diff)
        r.statements.map { it.sql }.first { it.contains("CREATE TABLE \"tickets\"") } shouldContainStr "\"status\" TEXT"
        r.diagnostics.any { it.code == "W134" } shouldBe true
    }

    test("inline-values enum via ADD COLUMN → bare TEXT but LOUD via W134 (Review F1)") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "tickets",
                    columnsAdded = mapOf("status" to ColumnDefinition(NeutralType.Enum(values = listOf("a", "b")))),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.statements.map { it.sql }.any { it.contains("ADD COLUMN") && it.contains("\"status\" TEXT") } shouldBe true
        r.diagnostics.any { it.code == "W134" } shouldBe true
    }
})
