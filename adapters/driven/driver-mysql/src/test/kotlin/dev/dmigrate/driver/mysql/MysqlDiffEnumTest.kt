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
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Enum-Degradations-Slice (AP1). A MySQL enum renders as a native `ENUM('a','b')`
 * in the migrate/diff path — mirror of `schema generate` (MysqlEnumColumnRenderer)
 * instead of degrading to bare TEXT — and needs no W134 (native = faithful).
 */
class MysqlDiffEnumTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")
    fun planAndUp(diff: SchemaDiff) =
        gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

    test("CreateTable enum renders native ENUM, not TEXT, and does not warn W134") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "status" to ColumnDefinition(NeutralType.Enum(values = listOf("open", "closed")), required = true),
            ),
            primaryKey = listOf("id"),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("tickets", table)))
        val r = planAndUp(diff)
        r.statements.map { it.sql }.first { it.contains("CREATE TABLE") } shouldContainStr "ENUM('open', 'closed')"
        r.diagnostics.any { it.code == "W134" } shouldBe false
    }

    test("ADD COLUMN enum renders native ENUM, not TEXT") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "tickets",
                    columnsAdded = mapOf("prio" to ColumnDefinition(NeutralType.Enum(values = listOf("lo", "hi")))),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.statements.map { it.sql }.any { it.contains("ADD COLUMN") && it.contains("ENUM('lo', 'hi')") } shouldBe true
    }
})
