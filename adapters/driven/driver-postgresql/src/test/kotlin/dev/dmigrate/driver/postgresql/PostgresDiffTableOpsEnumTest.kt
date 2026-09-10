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
 * Wie der PostgreSQL-Migrationspfad ein Enum rendert:
 *
 * - ein `refType`-Enum verweist auf seinen nativen Typ, und der `CREATE TYPE`
 *   steht vor der Tabelle;
 * - ein Inline-Enum wird zur Textspalte, deren Wertevorrat ein `CHECK`
 *   durchsetzt — dieselbe Form, die `schema generate` schreibt;
 * - `W134` bleibt fuer den Fall, in dem nichts durchzusetzen ist.
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

    test("an inline enum becomes TEXT with the CHECK that enforces its values (CreateTable)") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "status" to ColumnDefinition(NeutralType.Enum(values = listOf("open", "closed"))),
            ),
            primaryKey = listOf("id"),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("tickets", table)))
        val r = planAndUp(diff)
        val createTable = r.statements.map { it.sql }.first { it.contains("CREATE TABLE \"tickets\"") }
        createTable shouldContainStr "\"status\" TEXT"
        createTable shouldContainStr "CHECK (\"status\" IN ('open', 'closed'))"
        // Nichts geht mehr verloren, also gibt es auch nichts zu melden.
        r.diagnostics.any { it.code == "W134" } shouldBe false
    }

    test("an inline enum added by ALTER carries the same CHECK") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "tickets",
                    columnsAdded = mapOf("status" to ColumnDefinition(NeutralType.Enum(values = listOf("a", "b")))),
                ),
            ),
        )
        val r = planAndUp(diff)
        val addColumn = r.statements.map { it.sql }.first { it.contains("ADD COLUMN") }
        addColumn shouldContainStr "\"status\" TEXT"
        addColumn shouldContainStr "CHECK (\"status\" IN ('a', 'b'))"
        r.diagnostics.any { it.code == "W134" } shouldBe false
    }

    test("an enum without values keeps the warning — there is nothing to enforce") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "tickets",
                    columnsAdded = mapOf("status" to ColumnDefinition(NeutralType.Enum())),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.statements.map { it.sql }.first { it.contains("ADD COLUMN") } shouldContainStr "\"status\" TEXT"
        r.diagnostics.any { it.code == "W134" } shouldBe true
    }
})
