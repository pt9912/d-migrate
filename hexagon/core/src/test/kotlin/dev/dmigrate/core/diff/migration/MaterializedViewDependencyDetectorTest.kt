package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Plan-2 §8 D.3b Sub-Slice C: pin the planner-side dependency-graph
 * checks. Covers Drop/Replace of a depended-on table/view/routine
 * with and without a matching MV Drop/Replace, plus Cross-MV
 * dependency chains.
 */
class MaterializedViewDependencyDetectorTest : FunSpec({

    val planner = DiffPlanner()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")
    fun simpleTable() = TableDefinition(
        columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
    )

    test("Drop of a depended-on table without MV-Drop blocks with BLOCKED_DEPENDENCY_UNRESOLVED") {
        val mv = ViewDefinition(
            query = "SELECT id FROM users",
            materialized = true,
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val current = emptySchema().copy(
            tables = mapOf("users" to simpleTable()),
            views = mapOf("mv_users" to mv),
        )
        val desired = emptySchema().copy(views = mapOf("mv_users" to mv))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(tablesRemoved = listOf(NamedTable("users", simpleTable()))),
        )

        val dropTable = plan.operations.filterIsInstance<DiffOperation.DropTable>().single()
        val blocker = plan.materializedViewDependencyBlockers.single()
        blocker.materializedViewName shouldBe "mv_users"
        blocker.droppingOperationId shouldBe dropTable.id
        blocker.droppingKind shouldBe "TABLE"

        val diagnostic = plan.diagnostics.single { it.code == "BLOCKED_DEPENDENCY_UNRESOLVED" }
        diagnostic.severity shouldBe DiffDiagnostic.Severity.BLOCKER
        diagnostic.operationId shouldBe dropTable.id
        diagnostic.message shouldContain "mv_users"
        diagnostic.message shouldContain "users"
    }

    test("Drop of a depended-on table WITH MV-Drop emits no dependency blocker") {
        val mv = ViewDefinition(
            query = "SELECT id FROM users",
            materialized = true,
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val current = emptySchema().copy(
            tables = mapOf("users" to simpleTable()),
            views = mapOf("mv_users" to mv),
        )
        val desired = emptySchema()
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(
                tablesRemoved = listOf(NamedTable("users", simpleTable())),
                viewsRemoved = listOf(NamedView("mv_users", mv)),
            ),
        )

        plan.materializedViewDependencyBlockers.shouldBeEmpty()
        plan.diagnostics.filter { it.code == "BLOCKED_DEPENDENCY_UNRESOLVED" }.shouldBeEmpty()
        // The topological sort places DropMaterializedView BEFORE DropTable.
        // Plain-ordering check (not strict adjacency) to keep the test
        // robust against future ops slipping between them.
        val dropTable = plan.operations.filterIsInstance<DiffOperation.DropTable>().single()
        val dropMv = plan.operations.filterIsInstance<DiffOperation.DropMaterializedView>().single()
        (plan.operations.indexOf(dropMv) < plan.operations.indexOf(dropTable)) shouldBe true
        dropTable.dependencies.contains(dropMv.id) shouldBe true
    }

    test("Replace of a depended-on table without MV-Replace blocks") {
        val mv = ViewDefinition(
            query = "SELECT id FROM users",
            materialized = true,
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val before = ViewDefinition(query = "SELECT 1 FROM users")
        val after = ViewDefinition(query = "SELECT 2 FROM users")
        // The "Replace" target is a regular view, not a table — `OperationMapper`
        // only emits ReplaceView (which is what the plan §5 test wording
        // means by "Replace einer Tabelle/View/Routine"). The MV depends
        // on the regular view's body via dependencies.views.
        val mvWithViewDep = mv.copy(
            query = "SELECT * FROM v_users",
            dependencies = DependencyInfo(views = listOf("v_users")),
        )
        val current = emptySchema().copy(
            views = mapOf(
                "v_users" to before,
                "mv_users" to mvWithViewDep,
            ),
        )
        val desired = emptySchema().copy(
            views = mapOf(
                "v_users" to after,
                "mv_users" to mvWithViewDep,
            ),
        )
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(
                viewsChanged = listOf(
                    ViewDiff(name = "v_users", query = ValueChange("SELECT 1 FROM users", "SELECT 2 FROM users")),
                ),
            ),
        )

        val blocker = plan.materializedViewDependencyBlockers.single()
        blocker.materializedViewName shouldBe "mv_users"
        blocker.droppingKind shouldBe "VIEW"
        plan.diagnostics.any { it.code == "BLOCKED_DEPENDENCY_UNRESOLVED" } shouldBe true
    }

    test("ReplaceFunction wires a topology edge so MV-Create runs after the routine Replace") {
        // Regression pin: previously `createFunctionId` only indexed
        // `CreateFunction` ops, so a `CreateMaterializedView` that
        // depended on `fn_x` had no explicit edge to a co-resident
        // `ReplaceFunction fn_x`. The phase tie-breaker happened to
        // produce the correct ROUTINES→VIEWS order, but the topology
        // was fragile. Sub-Slice C extends the index to include
        // `ReplaceFunction` / `ReplaceProcedure` so the edge is now
        // explicit.
        val fnBefore = dev.dmigrate.core.model.FunctionDefinition(
            language = "sql",
            body = "RETURN 1",
        )
        val fnAfter = fnBefore.copy(body = "RETURN 2")
        val mv = ViewDefinition(
            query = "SELECT fn_x() AS v",
            materialized = true,
            dependencies = DependencyInfo(functions = listOf("fn_x")),
        )
        val current = emptySchema().copy(functions = mapOf("fn_x" to fnBefore))
        val desired = emptySchema().copy(
            functions = mapOf("fn_x" to fnAfter),
            views = mapOf("mv_x" to mv),
        )
        val plan = planner.plan(
            current,
            desired,
            dev.dmigrate.core.diff.SchemaDiff(
                viewsAdded = listOf(NamedView("mv_x", mv)),
                functionsChanged = listOf(
                    dev.dmigrate.core.diff.FunctionDiff(
                        name = "fn_x",
                    ),
                ),
            ),
        )

        val replaceFn = plan.operations.filterIsInstance<DiffOperation.ReplaceFunction>().single()
        val createMv = plan.operations.filterIsInstance<DiffOperation.CreateMaterializedView>().single()
        createMv.dependencies.contains(replaceFn.id) shouldBe true
        // Sanity: topological order also places the routine replace
        // before the MV create. Use a plain ordering check (not strict
        // adjacency) so the test stays robust if a future change
        // interleaves another op between them.
        (plan.operations.indexOf(replaceFn) < plan.operations.indexOf(createMv)) shouldBe true
    }

    test("Drop+Replace symmetry: DropMV that referenced fn_x runs before ReplaceFunction fn_x") {
        // Plan-2 §8 D.3b Sub-Slice C review fix: when both `DropMV mv_x`
        // (with `mv_x.dependencies.functions = [fn_x]`) AND
        // `ReplaceFunction fn_x` are in the same plan, the reverse-
        // topology edge attaches `DropMV.id` to `ReplaceFunction.dependencies`
        // so the Drop runs first regardless of the phase tie-breaker.
        val fnBefore = dev.dmigrate.core.model.FunctionDefinition(language = "sql", body = "RETURN 1")
        val fnAfter = fnBefore.copy(body = "RETURN 2")
        val mv = ViewDefinition(
            query = "SELECT fn_x() AS v",
            materialized = true,
            dependencies = DependencyInfo(functions = listOf("fn_x")),
        )
        val current = emptySchema().copy(
            functions = mapOf("fn_x" to fnBefore),
            views = mapOf("mv_x" to mv),
        )
        val desired = emptySchema().copy(functions = mapOf("fn_x" to fnAfter))
        val plan = planner.plan(
            current,
            desired,
            dev.dmigrate.core.diff.SchemaDiff(
                viewsRemoved = listOf(NamedView("mv_x", mv)),
                functionsChanged = listOf(dev.dmigrate.core.diff.FunctionDiff(name = "fn_x")),
            ),
        )

        val replaceFn = plan.operations.filterIsInstance<DiffOperation.ReplaceFunction>().single()
        val dropMv = plan.operations.filterIsInstance<DiffOperation.DropMaterializedView>().single()
        replaceFn.dependencies.contains(dropMv.id) shouldBe true
        // Ordering: dropMv runs before replaceFn.
        (plan.operations.indexOf(dropMv) < plan.operations.indexOf(replaceFn)) shouldBe true
    }

    test("Cross-MV: drop MV-B without dropping MV-A (which depends on MV-B) blocks") {
        val mvB = ViewDefinition(query = "SELECT 1", materialized = true)
        val mvA = ViewDefinition(
            query = "SELECT * FROM mv_b",
            materialized = true,
            dependencies = DependencyInfo(views = listOf("mv_b")),
        )
        val current = emptySchema().copy(views = mapOf("mv_a" to mvA, "mv_b" to mvB))
        val desired = emptySchema().copy(views = mapOf("mv_a" to mvA))
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(viewsRemoved = listOf(NamedView("mv_b", mvB))),
        )

        val blocker = plan.materializedViewDependencyBlockers.single()
        blocker.materializedViewName shouldBe "mv_a"
        blocker.droppingKind shouldBe "MATERIALIZED_VIEW"
        val dropMvB = plan.operations.filterIsInstance<DiffOperation.DropMaterializedView>().single()
        blocker.droppingOperationId shouldBe dropMvB.id
    }

    test("AlterColumnType on a depended-on table without MV-Replace blocks (Sub-Slice C review extension)") {
        // Plan-2 §8 D.3b Sub-Slice C review fix: column-level mutations
        // on a depended-on table can silently invalidate the MV body
        // at `REFRESH` time. The detector now treats `AlterColumnType` /
        // `AlterColumnNullability` / `AlterColumnDefault` / `DropColumn`
        // on a column whose parent table is in the MV's
        // `dependencies.tables` as an orphan trigger.
        val beforeUsers = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "name" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val afterUsers = beforeUsers.copy(
            columns = beforeUsers.columns + ("name" to ColumnDefinition(NeutralType.Text(maxLength = 100))),
        )
        val mv = ViewDefinition(
            query = "SELECT id, name FROM users",
            materialized = true,
            dependencies = DependencyInfo(
                tables = listOf("users"),
                columns = mapOf("users" to listOf("name")),
            ),
        )
        val current = emptySchema().copy(
            tables = mapOf("users" to beforeUsers),
            views = mapOf("mv_users" to mv),
        )
        val desired = emptySchema().copy(
            tables = mapOf("users" to afterUsers),
            views = mapOf("mv_users" to mv),
        )
        val plan = planner.plan(
            current,
            desired,
            dev.dmigrate.core.diff.SchemaDiff(
                tablesChanged = listOf(
                    dev.dmigrate.core.diff.TableDiff(
                        name = "users",
                        columnsChanged = listOf(
                            dev.dmigrate.core.diff.ColumnDiff(
                                name = "name",
                                type = ValueChange(NeutralType.Text(), NeutralType.Text(maxLength = 100)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val blocker = plan.materializedViewDependencyBlockers.single()
        blocker.materializedViewName shouldBe "mv_users"
        blocker.droppingKind shouldBe "TABLE"
        blocker.droppingPath shouldBe listOf("users", "name")
        plan.diagnostics.any { it.code == "BLOCKED_DEPENDENCY_UNRESOLVED" } shouldBe true
    }

    test("MV not in either schema produces no blocker") {
        // Sanity: schema without any materialized view never trips the
        // detector even if the plan drops a table.
        val current = emptySchema().copy(tables = mapOf("orders" to simpleTable()))
        val desired = emptySchema()
        val plan = planner.plan(
            current,
            desired,
            SchemaDiff(tablesRemoved = listOf(NamedTable("orders", simpleTable()))),
        )

        plan.materializedViewDependencyBlockers.shouldBeEmpty()
    }
})
