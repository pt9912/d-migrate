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
import io.kotest.matchers.collections.shouldNotContain
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

    test("§F.5 Sub-Slice A: added CHECK constraint flows through the mapper as AddConstraint op") {
        // Sub-Slice A removes the planner-level CONSTRAINT_NOT_DIFFABLE
        // blanket. The mapper emits AddConstraint(CHECK); the
        // renderer (Sub-Slice B/C/D) decides per dialect whether to
        // emit DDL or block with DIALECT_UNSUPPORTED_OPERATION. Until
        // those land, the planner-level result is no longer a blocker.
        val table = tableWithCheck()
        val result = planner.plan(
            current = schema(),
            desired = schema(mapOf("users" to table)),
            schemaDiff = SchemaDiff(tablesAdded = listOf(NamedTable("users", table))),
        )

        result.hasBlockers shouldBe false
        result.diagnostics.map { it.code } shouldNotContain "CONSTRAINT_NOT_DIFFABLE"
        // CreateTable for "users" is emitted (the table is no longer
        // in `blockedTables`).
        result.operations.filterIsInstance<DiffOperation.CreateTable>()
            .any { it.objectRef.rootName == "users" } shouldBe true
    }

    test("§F.5 Sub-Slice A: changed CHECK constraint emits Drop+Add ops") {
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

        result.hasBlockers shouldBe false
        result.diagnostics.map { it.code } shouldNotContain "CONSTRAINT_NOT_DIFFABLE"
        // Mapper emits Drop+Add (per the standard constraint-change
        // pattern); the renderer (B/C/D) will decide whether to
        // render or block these CHECK-typed ops.
        result.operations.filterIsInstance<DiffOperation.DropConstraint>()
            .any { it.constraint.name == "chk_age" } shouldBe true
        result.operations.filterIsInstance<DiffOperation.AddConstraint>()
            .any { it.constraint.name == "chk_age" } shouldBe true
    }

    test("§F.5 Sub-Slice A: changed EXCLUDE constraint emits Drop+Add ops") {
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

        result.hasBlockers shouldBe false
        result.diagnostics.map { it.code } shouldNotContain "CONSTRAINT_NOT_DIFFABLE"
        result.operations.filterIsInstance<DiffOperation.DropConstraint>()
            .any { it.constraint.name == "exclude_room_overlap" } shouldBe true
        result.operations.filterIsInstance<DiffOperation.AddConstraint>()
            .any { it.constraint.name == "exclude_room_overlap" } shouldBe true
    }

    test("§F.5 Sub-Slice A: CHECK with subquery blocks with CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED") {
        // Cross-table heuristic positive case — the expression
        // contains a SELECT token at word boundary, so the planner
        // blocks the entire table and skips it in the mapper.
        val crossTableConstraint = ConstraintDefinition(
            name = "chk_orders_count",
            type = ConstraintType.CHECK,
            expression = "(SELECT count(*) FROM other_table) > 0",
        )
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            constraints = listOf(crossTableConstraint),
            primaryKey = listOf("id"),
        )

        val result = planner.plan(
            current = schema(),
            desired = schema(mapOf("users" to table)),
            schemaDiff = SchemaDiff(tablesAdded = listOf(NamedTable("users", table))),
        )

        result.hasBlockers shouldBe true
        result.diagnostics.map { it.code } shouldContain "CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED"
        // The table sits in `blockedTables`, so the mapper skips
        // its CreateTable op.
        result.operations.filterIsInstance<DiffOperation.CreateTable>()
            .any { it.objectRef.rootName == "users" } shouldBe false
    }

    test("§F.5 Sub-Slice A: CHECK with SQL line comment containing 'SELECT' does NOT trigger heuristic") {
        // SQL line comments are stripped before the heuristic runs.
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            constraints = listOf(
                ConstraintDefinition(
                    name = "chk_age",
                    type = ConstraintType.CHECK,
                    expression = "age >= 0 -- avoid SELECT-style joins, see ADR-0007",
                ),
            ),
            primaryKey = listOf("id"),
        )

        val result = planner.plan(
            current = schema(),
            desired = schema(mapOf("users" to table)),
            schemaDiff = SchemaDiff(tablesAdded = listOf(NamedTable("users", table))),
        )

        result.diagnostics.map { it.code } shouldNotContain "CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED"
    }
})
