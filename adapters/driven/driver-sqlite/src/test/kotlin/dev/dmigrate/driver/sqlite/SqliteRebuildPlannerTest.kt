package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class SqliteRebuildPlannerTest : FunSpec({

    val planner = DiffPlanner()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    test("classify on diff without rebuild triggers leaves all ops as simple") {
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("u", TableDefinition())),
            tablesChanged = listOf(
                TableDiff(
                    name = "v",
                    indicesAdded = listOf(
                        IndexDefinition(name = "i", columns = listOf(IndexColumn("c")), type = IndexType.BTREE),
                    ),
                ),
            ),
        )
        val ops = planner.plan(emptySchema(), emptySchema(), diff).operations
        val c = SqliteRebuildPlanner.classify(ops)
        c.rebuildBuckets shouldBe emptyMap()
        c.simpleOps.size shouldBe ops.size
    }

    test("AlterColumnType triggers a rebuild bucket; AddColumn on same table is absorbed") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsAdded = mapOf("nick" to ColumnDefinition(NeutralType.Text())),
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val ops = planner.plan(emptySchema(), emptySchema(), diff).operations
        val c = SqliteRebuildPlanner.classify(ops)
        c.rebuildBuckets.keys shouldBe setOf("u")
        // Both AddColumn(nick) and AlterColumnType(age) are absorbed into the rebuild.
        c.rebuildBuckets.getValue("u").size shouldBe 2
        c.simpleOps.shouldBeEmpty()
    }

    test("Index ops on a rebuild table stay as simple ops (run after rebuild)") {
        val idx = IndexDefinition(name = "i", columns = listOf(IndexColumn("c")), type = IndexType.BTREE)
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                    indicesAdded = listOf(idx),
                ),
            ),
        )
        val ops = planner.plan(emptySchema(), emptySchema(), diff).operations
        val c = SqliteRebuildPlanner.classify(ops)
        c.rebuildBuckets.getValue("u").any { it is DiffOperation.AlterColumnType } shouldBe true
        c.simpleOps.any { it is DiffOperation.AddIndex } shouldBe true
    }

    test("tempTableName is deterministic given identical bucket op-ids") {
        val bucket = listOf(
            DiffOperation.AddColumn(
                id = "op-a",
                objectRef = dev.dmigrate.core.diff.migration.DiffObjectRef(
                    dev.dmigrate.core.diff.migration.DiffObjectType.COLUMN,
                    listOf("u", "x"),
                ),
                column = ColumnDefinition(NeutralType.Text()),
            ),
            DiffOperation.AddColumn(
                id = "op-b",
                objectRef = dev.dmigrate.core.diff.migration.DiffObjectRef(
                    dev.dmigrate.core.diff.migration.DiffObjectType.COLUMN,
                    listOf("u", "y"),
                ),
                column = ColumnDefinition(NeutralType.Text()),
            ),
        )
        val n1 = SqliteRebuildPlanner.tempTableName("u", bucket)
        val n2 = SqliteRebuildPlanner.tempTableName("u", bucket.reversed())
        n1 shouldBe n2  // sort-by-id makes order irrelevant
        n1.startsWith("u__dmg_rebuild_") shouldBe true
    }
})
