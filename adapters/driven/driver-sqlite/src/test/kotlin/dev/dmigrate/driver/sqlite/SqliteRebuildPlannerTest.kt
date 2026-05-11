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

    // ---- Phase H.2: temp-name collision resolution ----

    val sampleBucket = listOf(
        DiffOperation.AlterColumnType(
            id = "op-1",
            objectRef = dev.dmigrate.core.diff.migration.DiffObjectRef(
                dev.dmigrate.core.diff.migration.DiffObjectType.COLUMN,
                listOf("u", "x"),
            ),
            before = NeutralType.Integer,
            after = NeutralType.BigInteger,
        ),
    )

    test("H.2 — resolveTempTableName returns the base name when catalog is empty") {
        val name = SqliteRebuildPlanner.resolveTempTableName("u", sampleBucket, SqliteCatalogSnapshot.EMPTY)
        name shouldBe SqliteRebuildPlanner.tempTableName("u", sampleBucket)
    }

    test("H.2 — collision with an existing table appends `__2`") {
        val base = SqliteRebuildPlanner.tempTableName("u", sampleBucket)
        val catalog = SqliteCatalogSnapshot.EMPTY.copy(tables = setOf(base))
        SqliteRebuildPlanner.resolveTempTableName("u", sampleBucket, catalog) shouldBe "${base}__2"
    }

    test("H.2 — collision against a view (not just a table) also triggers __2") {
        val base = SqliteRebuildPlanner.tempTableName("u", sampleBucket)
        val catalog = SqliteCatalogSnapshot.EMPTY.copy(views = setOf(base))
        SqliteRebuildPlanner.resolveTempTableName("u", sampleBucket, catalog) shouldBe "${base}__2"
    }

    test("H.2 — collision against an index name also triggers __2") {
        val base = SqliteRebuildPlanner.tempTableName("u", sampleBucket)
        val catalog = SqliteCatalogSnapshot.EMPTY.copy(indices = setOf(base))
        SqliteRebuildPlanner.resolveTempTableName("u", sampleBucket, catalog) shouldBe "${base}__2"
    }

    test("H.2 — collision against a trigger name also triggers __2") {
        val base = SqliteRebuildPlanner.tempTableName("u", sampleBucket)
        val catalog = SqliteCatalogSnapshot.EMPTY.copy(triggers = setOf(base))
        SqliteRebuildPlanner.resolveTempTableName("u", sampleBucket, catalog) shouldBe "${base}__2"
    }

    test("H.2 — when `__2` is also taken, falls back to `__3`") {
        val base = SqliteRebuildPlanner.tempTableName("u", sampleBucket)
        val catalog = SqliteCatalogSnapshot.EMPTY.copy(
            tables = setOf(base, "${base}__2"),
        )
        SqliteRebuildPlanner.resolveTempTableName("u", sampleBucket, catalog) shouldBe "${base}__3"
    }

    test("H.2 — fromSchema synthesises tables + views + triggers + index names") {
        val schema = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_users_id",
                            columns = listOf(IndexColumn("id")),
                            type = IndexType.BTREE,
                        ),
                    ),
                ),
                "anon_idx_table" to TableDefinition(
                    columns = mapOf("c" to ColumnDefinition(NeutralType.Text())),
                    indices = listOf(
                        IndexDefinition(
                            name = null, // anonymous → fallback name
                            columns = listOf(IndexColumn("c")),
                            type = IndexType.BTREE,
                        ),
                    ),
                ),
            ),
            views = mapOf("v_users" to dev.dmigrate.core.model.ViewDefinition(query = "SELECT id FROM users")),
        )
        val snap = SqliteCatalogSnapshot.fromSchema(schema)
        snap.tables shouldBe setOf("users", "anon_idx_table")
        snap.views shouldBe setOf("v_users")
        snap.indices shouldBe setOf("idx_users_id", "anon_idx_table_c_idx")
        snap.triggers.shouldBeEmpty()
    }

    test("H.2 — union of two snapshots") {
        val a = SqliteCatalogSnapshot.EMPTY.copy(tables = setOf("a"))
        val b = SqliteCatalogSnapshot.EMPTY.copy(tables = setOf("b"))
        a.union(b).tables shouldBe setOf("a", "b")
    }

    test("H.2 — planRebuild uses the resolved (collision-aware) temp name") {
        val before = TableDefinition(columns = mapOf("x" to ColumnDefinition(NeutralType.Integer)))
        val after = before.copy(columns = mapOf("x" to ColumnDefinition(NeutralType.BigInteger)))
        val base = SqliteRebuildPlanner.tempTableName("u", sampleBucket)
        val catalog = SqliteCatalogSnapshot.EMPTY.copy(tables = setOf(base))

        val plan = SqliteRebuildPlanner.planRebuild(
            table = "u",
            bucket = sampleBucket,
            source = before,
            target = after,
            bucketRisk = dev.dmigrate.core.diff.migration.OperationRisk.SAFE,
            sql = SqliteDiffSqlBuilders(),
            catalog = catalog,
        )
        plan.newTableTempName shouldBe "${base}__2"
    }
})
