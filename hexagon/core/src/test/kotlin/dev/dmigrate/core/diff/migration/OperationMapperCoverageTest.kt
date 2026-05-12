package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.CustomTypeDiff
import dev.dmigrate.core.diff.FunctionDiff
import dev.dmigrate.core.diff.NamedCustomType
import dev.dmigrate.core.diff.NamedFunction
import dev.dmigrate.core.diff.NamedProcedure
import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.ProcedureDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.TriggerDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Exercises every mapping branch in [OperationMapper] that the
 * `DiffPlannerTest` happy paths leave uncovered: removed/changed
 * variants for views, sequences, functions, procedures, triggers,
 * indices, custom types — plus the anon-index fallback in
 * `indexRef`.
 */
class OperationMapperCoverageTest : FunSpec({

    val planner = DiffPlanner()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    test("custom types added / removed / changed all map") {
        val before = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a"))
        val after = before.copy(values = listOf("a", "b"))
        val current = emptySchema().copy(customTypes = mapOf("status_t" to before))
        val desired = emptySchema().copy(customTypes = mapOf("status_t" to after))
        val diff = SchemaDiff(
            customTypesAdded = listOf(NamedCustomType("new_t", before)),
            customTypesRemoved = listOf(NamedCustomType("old_t", before)),
            customTypesChanged = listOf(CustomTypeDiff(name = "status_t", values = ValueChange(listOf("a"), listOf("a", "b")))),
        )
        val result = planner.plan(current, desired, diff)
        result.operations.filterIsInstance<DiffOperation.CreateCustomType>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.DropCustomType>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.AlterCustomType>().size shouldBe 1
    }

    test("custom-type changed without matching definitions is silently skipped") {
        val diff = SchemaDiff(
            customTypesChanged = listOf(CustomTypeDiff(name = "missing_t")),
        )
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        result.operations.filterIsInstance<DiffOperation.AlterCustomType>().size shouldBe 0
    }

    test("views removed and changed map to DropView and ReplaceView") {
        val before = ViewDefinition(query = "SELECT 1")
        val after = ViewDefinition(query = "SELECT 2")
        val current = emptySchema().copy(views = mapOf("v_x" to before))
        val desired = emptySchema().copy(views = mapOf("v_x" to after))
        val diff = SchemaDiff(
            viewsRemoved = listOf(NamedView("v_old", before)),
            viewsChanged = listOf(ViewDiff(name = "v_x", query = ValueChange("SELECT 1", "SELECT 2"))),
        )
        val result = planner.plan(current, desired, diff)
        result.operations.filterIsInstance<DiffOperation.DropView>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.ReplaceView>().size shouldBe 1
    }

    test("view changed without matching definitions is silently skipped") {
        val diff = SchemaDiff(viewsChanged = listOf(ViewDiff(name = "missing_v")))
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        result.operations.filterIsInstance<DiffOperation.ReplaceView>().size shouldBe 0
    }

    test("sequences added / removed / changed all map") {
        val before = SequenceDefinition(start = 1)
        val after = SequenceDefinition(start = 100)
        val current = emptySchema().copy(sequences = mapOf("s_x" to before))
        val desired = emptySchema().copy(sequences = mapOf("s_x" to after))
        val diff = SchemaDiff(
            sequencesAdded = listOf(NamedSequence("s_new", before)),
            sequencesRemoved = listOf(NamedSequence("s_old", before)),
            sequencesChanged = listOf(SequenceDiff(name = "s_x", start = ValueChange(1, 100))),
        )
        val result = planner.plan(current, desired, diff)
        result.operations.filterIsInstance<DiffOperation.CreateSequence>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.DropSequence>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.AlterSequence>().size shouldBe 1
    }

    test("sequence changed without matching definitions is silently skipped") {
        val diff = SchemaDiff(sequencesChanged = listOf(SequenceDiff(name = "missing_s")))
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        result.operations.filterIsInstance<DiffOperation.AlterSequence>().size shouldBe 0
    }

    test("table and column defaults depending on new sequences are ordered after CreateSequence") {
        val sequence = SequenceDefinition(start = 10)
        val current = emptySchema().copy(
            tables = mapOf(
                "existing" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
                ),
            ),
        )
        val desired = emptySchema().copy(
            sequences = mapOf("invoice_seq" to sequence),
            tables = mapOf(
                "invoice" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(
                            NeutralType.Integer,
                            default = DefaultValue.SequenceNextVal("invoice_seq"),
                        ),
                    ),
                ),
                "existing" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Integer),
                        "invoice_id" to ColumnDefinition(
                            NeutralType.Integer,
                            default = DefaultValue.SequenceNextVal("invoice_seq"),
                        ),
                    ),
                ),
            ),
        )
        val diff = SchemaDiff(
            sequencesAdded = listOf(NamedSequence("invoice_seq", sequence)),
            tablesAdded = listOf(NamedTable("invoice", desired.tables.getValue("invoice"))),
            tablesChanged = listOf(
                TableDiff(
                    name = "existing",
                    columnsAdded = mapOf("invoice_id" to desired.tables.getValue("existing").columns.getValue("invoice_id")),
                ),
            ),
        )

        val result = planner.plan(current, desired, diff)
        val createSequence = result.operations.filterIsInstance<DiffOperation.CreateSequence>().single()
        val createTable = result.operations.filterIsInstance<DiffOperation.CreateTable>().single()
        val addColumn = result.operations.filterIsInstance<DiffOperation.AddColumn>().single()

        createTable.dependencies shouldBe setOf(createSequence.id)
        addColumn.dependencies shouldBe setOf(createSequence.id)
        (result.operations.indexOf(createSequence) < result.operations.indexOf(createTable)) shouldBe true
        (result.operations.indexOf(createSequence) < result.operations.indexOf(addColumn)) shouldBe true
    }

    test("functions added / removed / changed all map") {
        val before = FunctionDefinition()
        val after = FunctionDefinition()
        val current = emptySchema().copy(functions = mapOf("fn_x" to before))
        val desired = emptySchema().copy(functions = mapOf("fn_x" to after))
        val diff = SchemaDiff(
            functionsAdded = listOf(NamedFunction("fn_new", before)),
            functionsRemoved = listOf(NamedFunction("fn_old", before)),
            functionsChanged = listOf(FunctionDiff(name = "fn_x", body = ValueChange("a", "b"))),
        )
        val result = planner.plan(current, desired, diff)
        result.operations.filterIsInstance<DiffOperation.CreateFunction>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.DropFunction>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.ReplaceFunction>().size shouldBe 1
    }

    test("function changed without matching definitions is silently skipped") {
        val diff = SchemaDiff(functionsChanged = listOf(FunctionDiff(name = "missing_fn")))
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        result.operations.filterIsInstance<DiffOperation.ReplaceFunction>().size shouldBe 0
    }

    test("procedures added / removed / changed all map") {
        val before = ProcedureDefinition()
        val after = ProcedureDefinition()
        val current = emptySchema().copy(procedures = mapOf("sp_x" to before))
        val desired = emptySchema().copy(procedures = mapOf("sp_x" to after))
        val diff = SchemaDiff(
            proceduresAdded = listOf(NamedProcedure("sp_new", before)),
            proceduresRemoved = listOf(NamedProcedure("sp_old", before)),
            proceduresChanged = listOf(ProcedureDiff(name = "sp_x", body = ValueChange("a", "b"))),
        )
        val result = planner.plan(current, desired, diff)
        result.operations.filterIsInstance<DiffOperation.CreateProcedure>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.DropProcedure>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.ReplaceProcedure>().size shouldBe 1
    }

    test("procedure changed without matching definitions is silently skipped") {
        val diff = SchemaDiff(proceduresChanged = listOf(ProcedureDiff(name = "missing_sp")))
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        result.operations.filterIsInstance<DiffOperation.ReplaceProcedure>().size shouldBe 0
    }

    test("triggers added / removed / changed all map") {
        val before = TriggerDefinition(table = "t", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER)
        val after = before.copy(timing = TriggerTiming.BEFORE)
        val current = emptySchema().copy(triggers = mapOf("trg_x" to before))
        val desired = emptySchema().copy(triggers = mapOf("trg_x" to after))
        val diff = SchemaDiff(
            triggersAdded = listOf(NamedTrigger("trg_new", before)),
            triggersRemoved = listOf(NamedTrigger("trg_old", before)),
            triggersChanged = listOf(
                TriggerDiff(
                    name = "trg_x",
                    timing = ValueChange(TriggerTiming.AFTER, TriggerTiming.BEFORE),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        result.operations.filterIsInstance<DiffOperation.CreateTrigger>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.DropTrigger>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.ReplaceTrigger>().size shouldBe 1
    }

    test("trigger changed without matching definitions is silently skipped") {
        val diff = SchemaDiff(triggersChanged = listOf(TriggerDiff(name = "missing_trg")))
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        result.operations.filterIsInstance<DiffOperation.ReplaceTrigger>().size shouldBe 0
    }

    test("indices added / removed / changed produce Add/Drop pairs") {
        val idx1 = IndexDefinition(name = "idx_a", columns = listOf(IndexColumn("c1")), type = IndexType.BTREE)
        val idx2 = IndexDefinition(name = "idx_b", columns = listOf(IndexColumn("c2")), type = IndexType.BTREE)
        val before = IndexDefinition(name = "idx_old", columns = listOf(IndexColumn("c")), type = IndexType.BTREE)
        val after = IndexDefinition(name = "idx_new", columns = listOf(IndexColumn("c")), type = IndexType.HASH)
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    indicesAdded = listOf(idx1),
                    indicesRemoved = listOf(idx2),
                    indicesChanged = listOf(ValueChange(before, after)),
                ),
            ),
        )
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        result.operations.filterIsInstance<DiffOperation.AddIndex>().size shouldBe 2
        result.operations.filterIsInstance<DiffOperation.DropIndex>().size shouldBe 2
    }

    test("anonymous index (name == null) falls back to anon_<columnsKey>") {
        val anonIdx = IndexDefinition(name = null, columns = listOf(IndexColumn("c1")), type = IndexType.BTREE)
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(anonIdx))),
        )
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        val op = result.operations.filterIsInstance<DiffOperation.AddIndex>().single()
        op.objectRef.path[1].startsWith("anon_") shouldBe true
    }

    test("table with PK only-before yields just DropPrimaryKey (no AddPrimaryKey)") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "orders", primaryKey = ValueChange(before = listOf("id"), after = emptyList())),
            ),
        )
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        result.operations.filterIsInstance<DiffOperation.DropPrimaryKey>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.AddPrimaryKey>().size shouldBe 0
    }

    test("table with PK only-after yields just AddPrimaryKey (no DropPrimaryKey)") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "orders", primaryKey = ValueChange(before = emptyList(), after = listOf("id"))),
            ),
        )
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        result.operations.filterIsInstance<DiffOperation.DropPrimaryKey>().size shouldBe 0
        result.operations.filterIsInstance<DiffOperation.AddPrimaryKey>().size shouldBe 1
    }

    test("CHECK / EXCLUDE constraint on tablesChanged is filtered out of ops") {
        val check = dev.dmigrate.core.model.ConstraintDefinition(
            name = "chk_age",
            type = dev.dmigrate.core.model.ConstraintType.CHECK,
            expression = "age >= 0",
        )
        val exclude = dev.dmigrate.core.model.ConstraintDefinition(
            name = "ex_x",
            type = dev.dmigrate.core.model.ConstraintType.EXCLUDE,
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    constraintsAdded = listOf(check),
                    constraintsRemoved = listOf(exclude),
                ),
            ),
        )
        val result = planner.plan(emptySchema(), emptySchema(), diff)
        result.operations.filterIsInstance<DiffOperation.AddConstraint>().size shouldBe 0
        result.operations.filterIsInstance<DiffOperation.DropConstraint>().size shouldBe 0
    }

    test("blocked table (CHECK in current) skips tablesAdded entry as well") {
        val tableWithCheck = TableDefinition(
            columns = mapOf("age" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(
                dev.dmigrate.core.model.ConstraintDefinition(
                    name = "chk_age",
                    type = dev.dmigrate.core.model.ConstraintType.CHECK,
                    expression = "age >= 0",
                ),
            ),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("users", tableWithCheck)))
        val current = emptySchema()
        val desired = emptySchema().copy(tables = mapOf("users" to tableWithCheck))
        val result = planner.plan(current, desired, diff)
        result.operations.filterIsInstance<DiffOperation.CreateTable>()
            .any { it.objectRef.rootName == "users" } shouldBe false
        result.diagnostics.single().code shouldBe "CONSTRAINT_NOT_DIFFABLE"
    }
})
