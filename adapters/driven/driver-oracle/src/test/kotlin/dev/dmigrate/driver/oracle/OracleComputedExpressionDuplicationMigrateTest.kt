package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * oracle-doppelter-generierungsausdruck.md P2/P3: dieselben zwei Faelle
 * (E073 Ausdrucks-Duplikat, E074 Index-Kollision), diesmal auf dem
 * Migrate/Diff-Pfad -- ein Soll, das die zweite Spalte/den zweiten Index
 * erst nachtraeglich einfuehrt, faellt genauso vor der ersten Anweisung auf.
 */
class OracleComputedExpressionDuplicationMigrateTest : FunSpec({

    val gen = OracleDiffDdlGenerator()
    val planner = DiffPlanner()

    val idCol = "id" to ColumnDefinition(NeutralType.Integer, required = true)

    fun schemaWith(table: TableDefinition) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf("t" to table),
    )

    fun computedCol(expression: String) =
        ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed(expression))

    test("CreateTable with two duplicate-expression columns is blocked before the first statement (E073)") {
        val target = TableDefinition(
            columns = linkedMapOf(
                idCol,
                "x" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2")),
                "y" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2")),
            ),
            primaryKey = listOf("id"),
        )
        val result = gen.generateUp(
            planner.plan(
                SchemaDefinition(name = "App", version = "1"),
                schemaWith(target),
                SchemaDiff(tablesAdded = listOf(NamedTable("t", target))),
            ),
            DdlGenerationOptions(),
        )
        result.statements.shouldBeEmpty()
        val diag = result.diagnostics.single()
        diag.code shouldBe "E073"
        diag.message shouldContain "ORA-54015"
        diag.message shouldContain "'x'"
        diag.message shouldContain "'y'"
    }

    test("CreateTable with an expression index + a plain index on the same computed column is blocked (E074)") {
        val target = TableDefinition(
            columns = linkedMapOf(
                idCol,
                "x" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2")),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(columns = listOf(IndexColumn.expression("\"id\" * 2"))),
                IndexDefinition(columns = listOf(IndexColumn(name = "x"))),
            ),
        )
        val result = gen.generateUp(
            planner.plan(
                SchemaDefinition(name = "App", version = "1"),
                schemaWith(target),
                SchemaDiff(tablesAdded = listOf(NamedTable("t", target))),
            ),
            DdlGenerationOptions(),
        )
        result.statements.shouldBeEmpty()
        val diag = result.diagnostics.single()
        diag.code shouldBe "E074"
        diag.message shouldContain "ORA-01408"
    }

    test("AddColumn that duplicates an existing sibling's expression is blocked (E073)") {
        val before = TableDefinition(
            columns = linkedMapOf(idCol, "x" to computedCol("\"id\" * 2")),
            primaryKey = listOf("id"),
        )
        val newColumn = ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2"))
        val after = before.copy(columns = before.columns + ("y" to newColumn))
        val result = gen.generateUp(
            planner.plan(
                schemaWith(before), schemaWith(after),
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "t", columnsAdded = mapOf("y" to newColumn)))),
            ),
            DdlGenerationOptions(),
        )
        result.statements.shouldBeEmpty()
        val diag = result.diagnostics.single()
        diag.code shouldBe "E073"
        diag.message shouldContain "'x'"
        diag.message shouldContain "'y'"
    }

    test("AddColumn with a distinct expression is unaffected (regression guard)") {
        val before = TableDefinition(
            columns = linkedMapOf(idCol, "x" to computedCol("\"id\" * 2")),
            primaryKey = listOf("id"),
        )
        val newColumn = ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 3"))
        val after = before.copy(columns = before.columns + ("y" to newColumn))
        val result = gen.generateUp(
            planner.plan(
                schemaWith(before), schemaWith(after),
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "t", columnsAdded = mapOf("y" to newColumn)))),
            ),
            DdlGenerationOptions(),
        )
        result.diagnostics.shouldBeEmpty()
        result.statements.single().sql shouldContain "ADD"
    }

    test("AlterColumnGeneration that changes an expression to match a sibling is blocked (E073)") {
        val before = TableDefinition(
            columns = linkedMapOf(
                idCol,
                "x" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2")),
                "y" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 3")),
            ),
            primaryKey = listOf("id"),
        )
        val newGeneration = ColumnGeneration.Computed("\"id\" * 2")
        val after = before.copy(
            columns = before.columns + ("y" to before.columns.getValue("y").copy(generation = newGeneration)),
        )
        val result = gen.generateUp(
            planner.plan(
                schemaWith(before), schemaWith(after),
                SchemaDiff(
                    tablesChanged = listOf(
                        TableDiff(
                            name = "t",
                            columnsChanged = listOf(
                                ColumnDiff(
                                    name = "y",
                                    generation = ValueChange(
                                        before.columns.getValue("y").generation, newGeneration,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            DdlGenerationOptions(),
        )
        result.statements.shouldBeEmpty()
        val diag = result.diagnostics.single()
        diag.code shouldBe "E073"
    }

    test("AddIndex that introduces the plain-column half of the collision is blocked (E074)") {
        val before = TableDefinition(
            columns = linkedMapOf(idCol, "x" to computedCol("\"id\" * 2")),
            primaryKey = listOf("id"),
            indices = listOf(IndexDefinition(columns = listOf(IndexColumn.expression("\"id\" * 2")))),
        )
        val newIndex = IndexDefinition(columns = listOf(IndexColumn(name = "x")))
        val after = before.copy(indices = before.indices + newIndex)
        val result = gen.generateUp(
            planner.plan(
                schemaWith(before), schemaWith(after),
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "t", indicesAdded = listOf(newIndex)))),
            ),
            DdlGenerationOptions(),
        )
        result.statements.shouldBeEmpty()
        val diag = result.diagnostics.single()
        diag.code shouldBe "E074"
        diag.message shouldContain "ORA-01408"
    }

    test("AddIndex without a matching counterpart is unaffected (regression guard)") {
        val before = TableDefinition(
            columns = linkedMapOf(idCol, "x" to computedCol("\"id\" * 2")),
            primaryKey = listOf("id"),
        )
        val newIndex = IndexDefinition(columns = listOf(IndexColumn(name = "x")))
        val after = before.copy(indices = before.indices + newIndex)
        val result = gen.generateUp(
            planner.plan(
                schemaWith(before), schemaWith(after),
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "t", indicesAdded = listOf(newIndex)))),
            ),
            DdlGenerationOptions(),
        )
        result.diagnostics.shouldBeEmpty()
        result.statements.single().sql shouldContain "CREATE INDEX"
    }
})
