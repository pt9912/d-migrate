package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainString

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

    test("§F.5 changed CHECK constraints block without emitting drop or add operations") {
        val beforeConstraint = ConstraintDefinition(
            name = "chk_age",
            type = ConstraintType.CHECK,
            expression = "age >= 0",
        )
        val afterConstraint = beforeConstraint.copy(expression = "age >= 18")
        val before = tableWithCheck().copy(constraints = listOf(beforeConstraint))
        val after = tableWithCheck().copy(constraints = listOf(afterConstraint))

        val result = planner.plan(
            current = schema(mapOf("users" to before)),
            desired = schema(mapOf("users" to after)),
            schemaDiff = SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "users",
                        constraintsChanged = listOf(ValueChange(beforeConstraint, afterConstraint)),
                    ),
                ),
            ),
        )

        result.hasBlockers shouldBe true
        result.diagnostics.single().code shouldBe "CONSTRAINT_NOT_DIFFABLE"
        result.diagnostics.single().message shouldContainString "users"
        result.operations.filterIsInstance<DiffOperation.DropConstraint>().size shouldBe 0
        result.operations.filterIsInstance<DiffOperation.AddConstraint>().size shouldBe 0
    }

    test("§F.5 changed EXCLUDE constraints block with the same conservative contract") {
        val beforeConstraint = ConstraintDefinition(
            name = "exclude_room_overlap",
            type = ConstraintType.EXCLUDE,
            expression = "room WITH =, during WITH &&",
        )
        val afterConstraint = beforeConstraint.copy(expression = "room_id WITH =, during WITH &&")
        val before = TableDefinition(
            columns = mapOf("room" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(beforeConstraint),
        )
        val after = before.copy(constraints = listOf(afterConstraint))

        val result = planner.plan(
            current = schema(mapOf("reservations" to before)),
            desired = schema(mapOf("reservations" to after)),
            schemaDiff = SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "reservations",
                        constraintsChanged = listOf(ValueChange(beforeConstraint, afterConstraint)),
                    ),
                ),
            ),
        )

        result.hasBlockers shouldBe true
        result.diagnostics.map { it.code } shouldContain "CONSTRAINT_NOT_DIFFABLE"
        result.operations shouldBe emptyList<DiffOperation>()
    }
})
