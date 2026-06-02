package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Phase H.3a: rebuild drops dependent views/triggers before
 * `DROP TABLE` and recreates them after RENAME, plus the
 * simpleOps-absorption contract so a view-op on a rebuilt-table-ref
 * doesn't double-emit.
 *
 * Split from [SqliteRebuildRendererTest] to keep that spec under
 * detekt's LargeClass threshold.
 */
class SqliteRebuildH3aTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    fun usersTable(extra: Map<String, ColumnDefinition> = emptyMap()) = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "age" to ColumnDefinition(NeutralType.SmallInt),
        ) + extra,
        primaryKey = listOf("id"),
    )

    // View-Deps with explicit column refs so F.6.b's
    // VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS check doesn't fire
    // on column-altering ops (we test the H.3a path here, not F.6.b).
    fun viewOnUsers(query: String, refColumns: List<String> = listOf("id")) = ViewDefinition(
        query = query,
        dependencies = DependencyInfo(
            tables = listOf("users"),
            columns = mapOf("users" to refColumns),
        ),
    )

    fun triggerOnUsers(body: String) = TriggerDefinition(
        table = "users",
        event = TriggerEvent.INSERT,
        timing = TriggerTiming.AFTER,
        forEach = TriggerForEach.ROW,
        body = body,
    )

    test("H.3a — view in current AND desired (referenced rebuild table) is dropped and recreated") {
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to usersTable()),
            views = mapOf("v_active" to viewOnUsers("SELECT id FROM users")),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "age" to ColumnDefinition(NeutralType.Integer),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            views = mapOf("v_active" to viewOnUsers("SELECT id FROM users")),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }

        // DROP VIEW comes before DROP TABLE.
        val dropViewIdx = sqls.indexOfFirst { it.startsWith("DROP VIEW IF EXISTS \"v_active\"") }
        val dropTableIdx = sqls.indexOfFirst { it.startsWith("DROP TABLE \"users\"") }
        val createViewIdx = sqls.indexOfFirst { it.startsWith("CREATE VIEW \"v_active\"") }
        val renameIdx = sqls.indexOfFirst { it.contains("RENAME TO \"users\"") }
        dropViewIdx shouldBe (dropTableIdx - 1)
        createViewIdx shouldBe (renameIdx + 1)
    }

    test("H.3a — view only in current (removed in same plan) is dropped but NOT recreated") {
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to usersTable()),
            views = mapOf("v_legacy" to viewOnUsers("SELECT id FROM users")),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "age" to ColumnDefinition(NeutralType.Integer),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            views = emptyMap(),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
            viewsRemoved = listOf(
                NamedView("v_legacy", viewOnUsers("SELECT id FROM users")),
            ),
        )
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        sqls.any { it.startsWith("DROP VIEW IF EXISTS \"v_legacy\"") } shouldBe true
        sqls.any { it.startsWith("CREATE VIEW \"v_legacy\"") } shouldBe false
    }

    test("H.3a — view only in desired (added in same plan) is created AFTER RENAME, not before") {
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to usersTable()),
            views = emptyMap(),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "age" to ColumnDefinition(NeutralType.Integer),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            views = mapOf("v_new" to viewOnUsers("SELECT id FROM users")),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
            viewsAdded = listOf(NamedView("v_new", viewOnUsers("SELECT id FROM users"))),
        )
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        sqls.any { it.startsWith("CREATE VIEW \"v_new\"") } shouldBe true
        sqls.any { it.startsWith("DROP VIEW IF EXISTS \"v_new\"") } shouldBe false
        val createViewIdx = sqls.indexOfFirst { it.startsWith("CREATE VIEW \"v_new\"") }
        val renameIdx = sqls.indexOfFirst { it.contains("RENAME TO \"users\"") }
        (createViewIdx > renameIdx) shouldBe true
    }

    test("H.3a — trigger on rebuilt table is dropped before DROP TABLE and recreated after RENAME") {
        val trigger = triggerOnUsers("UPDATE users SET id = id + 1 WHERE id < 0;")
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to usersTable()),
            triggers = mapOf("trg_users_after_insert" to trigger),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "age" to ColumnDefinition(NeutralType.Integer),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            triggers = mapOf("trg_users_after_insert" to trigger),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        sqls.any { it.startsWith("DROP TRIGGER IF EXISTS \"trg_users_after_insert\"") } shouldBe true
        sqls.any { it.startsWith("CREATE TRIGGER \"trg_users_after_insert\"") } shouldBe true
    }

    test("H.3a — view-op on rebuilt-table-ref is absorbed (sourceOperationIds carries the id)") {
        // ReplaceView on a view that references a rebuilt table —
        // before-state: `SELECT id FROM users`, after-state changes
        // the query. The rebuild absorbs the ReplaceView so the
        // simpleOp path doesn't double-emit.
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to usersTable()),
            views = mapOf("v_users" to viewOnUsers("SELECT id FROM users")),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "age" to ColumnDefinition(NeutralType.Integer),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            views = mapOf("v_users" to viewOnUsers("SELECT id, age FROM users")),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
            viewsChanged = listOf(
                dev.dmigrate.core.diff.ViewDiff(
                    name = "v_users",
                    query = ValueChange(
                        "SELECT id FROM users",
                        "SELECT id, age FROM users",
                    ),
                ),
            ),
        )
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }

        // No double emission: exactly one DROP VIEW IF EXISTS for v_users
        // and exactly one CREATE VIEW for v_users.
        sqls.count { it.startsWith("DROP VIEW IF EXISTS \"v_users\"") } shouldBe 1
        sqls.count { it.startsWith("CREATE VIEW \"v_users\"") } shouldBe 1

        // The ReplaceView's op-id must be in some rebuild-tagged statement's
        // operationIds (sourceOperationIds carries it). ID format is
        // `<OpKind>:<type>:<pathHash>:<payloadHash>` per OperationIdFactory.
        val replaceViewOp = r.statements
            .flatMap { it.operationIds }
            .toSet()
        replaceViewOp.any { it.startsWith("ReplaceView:") } shouldBe true
    }

    test("H.3a — direct classify() absorbs view ops with rebuilt-table dependency") {
        // Unit-level check of the classify() absorption contract.
        val rebuildOp = DiffOperation.AlterColumnType(
            id = "alter-1",
            objectRef = dev.dmigrate.core.diff.migration.DiffObjectRef(
                dev.dmigrate.core.diff.migration.DiffObjectType.COLUMN,
                listOf("users", "age"),
            ),
            before = NeutralType.SmallInt,
            after = NeutralType.Integer,
        )
        val replaceViewOp = DiffOperation.ReplaceView(
            id = "replace-1",
            objectRef = dev.dmigrate.core.diff.migration.DiffObjectRef(
                dev.dmigrate.core.diff.migration.DiffObjectType.VIEW,
                listOf("v_users"),
            ),
            before = ViewDefinition(
                query = "SELECT id FROM users",
                dependencies = DependencyInfo(tables = listOf("users")),
            ),
            after = ViewDefinition(
                query = "SELECT id, age FROM users",
                dependencies = DependencyInfo(tables = listOf("users")),
            ),
        )
        val classification = SqliteRebuildPlanner.classify(listOf(rebuildOp, replaceViewOp))
        classification.rebuildBuckets["users"]!!.map { it.id } shouldContain "replace-1"
        classification.simpleOps.shouldBeEmpty()
    }

    test("H.3a — classify() does NOT absorb view ops whose deps don't touch a rebuilt table") {
        val rebuildOp = DiffOperation.AlterColumnType(
            id = "alter-1",
            objectRef = dev.dmigrate.core.diff.migration.DiffObjectRef(
                dev.dmigrate.core.diff.migration.DiffObjectType.COLUMN,
                listOf("users", "age"),
            ),
            before = NeutralType.SmallInt,
            after = NeutralType.Integer,
        )
        val unrelatedView = DiffOperation.CreateView(
            id = "create-unrelated",
            objectRef = dev.dmigrate.core.diff.migration.DiffObjectRef(
                dev.dmigrate.core.diff.migration.DiffObjectType.VIEW,
                listOf("v_other"),
            ),
            view = ViewDefinition(
                query = "SELECT 1",
                dependencies = DependencyInfo(tables = listOf("orders")),
            ),
        )
        val classification = SqliteRebuildPlanner.classify(listOf(rebuildOp, unrelatedView))
        classification.rebuildBuckets["users"]!!.map { it.id } shouldBe listOf("alter-1")
        classification.simpleOps.map { it.id } shouldBe listOf("create-unrelated")
    }

    test("H.3a — planner-direct: dependent views/triggers populated from schemas") {
        // Direct planRebuild call validates the field population without
        // the full DiffPlanner pipeline.
        val source = usersTable()
        val target = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "age" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
        )
        val sourceSchema = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to source),
            views = mapOf("v_legacy" to viewOnUsers("SELECT id FROM users")),
            triggers = mapOf("t1" to triggerOnUsers("SELECT 1;")),
        )
        val targetSchema = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to target),
            views = mapOf("v_new" to viewOnUsers("SELECT id, age FROM users")),
        )
        val plan = SqliteRebuildPlanner.planRebuild(
            table = "users",
            bucket = emptyList(),
            source = source,
            target = target,
            bucketRisk = dev.dmigrate.core.diff.migration.OperationRisk.SAFE,
            sql = SqliteDiffSqlBuilders(),
            sourceSchema = sourceSchema,
            targetSchema = targetSchema,
        )
        plan.dependentViewsToDrop.map { it.name } shouldBe listOf("v_legacy")
        plan.dependentViewsToRecreate.map { it.name } shouldBe listOf("v_new")
        plan.dependentTriggersToDrop.map { it.name } shouldBe listOf("t1")
        plan.dependentTriggersToRecreate.shouldBeEmpty()
    }

    test("H.3a — trigger without body produces SQLITE_REBUILD_TRIGGER_NOT_RENDERABLE blocker") {
        val brokenTrigger = TriggerDefinition(
            table = "users",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            forEach = TriggerForEach.ROW,
            body = null, // not renderable
        )
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to usersTable()),
            triggers = mapOf("trg_broken" to brokenTrigger),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "age" to ColumnDefinition(NeutralType.Integer),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            triggers = mapOf("trg_broken" to brokenTrigger),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.diagnostics.any { it.code == "SQLITE_REBUILD_TRIGGER_NOT_RENDERABLE" } shouldBe true
        val diag = r.diagnostics.single { it.code == "SQLITE_REBUILD_TRIGGER_NOT_RENDERABLE" }
        diag.message shouldContainStr "trg_broken"
    }
})
