package dev.dmigrate.driver.sqlite

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
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Enum-Degradations-Slice (AP3, W134). SQLite has no native enum type, so the
 * migrate/diff path renders every enum as bare TEXT — now LOUD via W134 at both
 * CreateTable and AddColumn (Review F1). Inline TEXT+CHECK fidelity is Option 2b.
 */
class SqliteDiffEnumDegradationTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")
    fun planAndUp(diff: SchemaDiff) =
        gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

    test("CreateTable enum → bare TEXT but LOUD via W134") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "status" to ColumnDefinition(NeutralType.Enum(values = listOf("open", "closed"))),
            ),
            primaryKey = listOf("id"),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("tickets", table)))
        val r = planAndUp(diff)
        r.statements.map { it.sql }.first { it.contains("CREATE TABLE") } shouldContainStr "\"status\" TEXT"
        r.diagnostics.any { it.code == "W134" } shouldBe true
    }

    test("ADD COLUMN enum → W134 (Review F1)") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "tickets",
                    columnsAdded = mapOf("prio" to ColumnDefinition(NeutralType.Enum(values = listOf("lo", "hi")))),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.diagnostics.any { it.code == "W134" } shouldBe true
    }
})
