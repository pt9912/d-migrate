package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class DiffPlannerF5ConstraintTest : FunSpec({
    val planner = DiffPlanner()

    fun schema(tables: Map<String, TableDefinition> = emptyMap()) = SchemaDefinition(
        name = "s",
        version = "1",
        tables = tables,
    )

    fun tableWithCheck(extraColumns: Map<String, ColumnDefinition> = emptyMap()) = TableDefinition(
        columns = mapOf("age" to ColumnDefinition(NeutralType.Integer)) + extraColumns,
        constraints = listOf(
            ConstraintDefinition(
                name = "chk_age",
                type = ConstraintType.CHECK,
                expression = "age >= 0",
            ),
        ),
    )

    test("§F.5 unchanged CHECK constraints do not block unrelated table changes") {
        val before = tableWithCheck()
        val addedColumn = ColumnDefinition(NeutralType.Text())
        val after = tableWithCheck(mapOf("name" to addedColumn))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("name" to addedColumn),
                ),
            ),
        )

        val result = planner.plan(
            current = schema(mapOf("users" to before)),
            desired = schema(mapOf("users" to after)),
            schemaDiff = diff,
        )

        result.hasBlockers shouldBe false
        result.operations.filterIsInstance<DiffOperation.AddColumn>()
            .single().objectRef.path shouldBe listOf("users", "name")
    }

    test("§F.5 added CHECK constraints still block with CONSTRAINT_NOT_DIFFABLE") {
        val table = tableWithCheck()
        val result = planner.plan(
            current = schema(),
            desired = schema(mapOf("users" to table)),
            schemaDiff = SchemaDiff(tablesAdded = listOf(NamedTable("users", table))),
        )

        result.hasBlockers shouldBe true
        result.diagnostics.map { it.code } shouldContain "CONSTRAINT_NOT_DIFFABLE"
        result.operations.filterIsInstance<DiffOperation.CreateTable>()
            .any { it.objectRef.rootName == "users" } shouldBe false
    }
})
