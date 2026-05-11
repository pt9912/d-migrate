package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Plan §G.2 / §10 L2096-L2099 coverage: the planner consumes
 * `DependencyInfo.projectionComplete` to detect adapter-side
 * incomplete view dependency projections (today only MySQL when
 * `VIEW_TABLE_USAGE` returns 0 rows for an existing view) and
 * blocks risky operations with
 * `VIEW_DEPENDENCY_PROJECTION_INCOMPLETE`.
 *
 * Split from [DiffPlannerTest] to keep the parent spec under
 * detekt's LargeClass threshold (Konvention: no `@Suppress`).
 */
class DiffPlannerG2Test : FunSpec({

    val planner = DiffPlanner()

    test("ReplaceView for a view with projectionComplete=false blocks") {
        val users = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "email" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val viewBefore = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(tables = emptyList(), projectionComplete = false),
        )
        val viewAfter = viewBefore.copy(query = "SELECT id, email FROM users")
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to viewBefore),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to viewAfter),
        )
        val diff = SchemaDiff(
            viewsChanged = listOf(
                ViewDiff(
                    name = "v_users",
                    query = ValueChange(viewBefore.query, viewAfter.query),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        val diag = result.diagnostics.single { it.code == "VIEW_DEPENDENCY_PROJECTION_INCOMPLETE" }
        diag.severity shouldBe DiffDiagnostic.Severity.BLOCKER
        diag.operationId shouldBe result.operations.filterIsInstance<DiffOperation.ReplaceView>().single().id
        diag.message shouldContainStr "v_users"
        diag.message shouldContainStr "VIEW_TABLE_USAGE"
    }

    test("ReplaceView for a view with projectionComplete=true does NOT block") {
        val users = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
        )
        val viewBefore = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(tables = listOf("users"), projectionComplete = true),
        )
        val viewAfter = viewBefore.copy(query = "SELECT id, id AS id2 FROM users")
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to viewBefore),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to viewAfter),
        )
        val diff = SchemaDiff(
            viewsChanged = listOf(
                ViewDiff(
                    name = "v_users",
                    query = ValueChange(viewBefore.query, viewAfter.query),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        result.diagnostics.any { it.code == "VIEW_DEPENDENCY_PROJECTION_INCOMPLETE" } shouldBe false
    }

    test("column-altering op on listed table of incomplete view blocks") {
        val users = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "email" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val view = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(
                tables = listOf("users"),
                columns = mapOf("users" to listOf("id", "email")),
                projectionComplete = false,
            ),
        )
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to view),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to users.copy(columns = users.columns - "email")),
            views = mapOf("v_users" to view),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsRemoved = mapOf("email" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        val diag = result.diagnostics.single { it.code == "VIEW_DEPENDENCY_PROJECTION_INCOMPLETE" }
        diag.operationId shouldBe result.operations.filterIsInstance<DiffOperation.DropColumn>().single().id
        diag.message shouldContainStr "users.email"
        diag.message shouldContainStr "v_users"
    }

    test("column-altering op on NON-listed table of incomplete view does NOT block") {
        val users = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
        )
        val orders = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "note" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val view = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(
                tables = listOf("users"), // 'orders' is NOT listed
                projectionComplete = false,
            ),
        )
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to users, "orders" to orders),
            views = mapOf("v_users" to view),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "users" to users,
                "orders" to orders.copy(columns = orders.columns - "note"),
            ),
            views = mapOf("v_users" to view),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "orders",
                    columnsRemoved = mapOf("note" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        result.diagnostics.any { it.code == "VIEW_DEPENDENCY_PROJECTION_INCOMPLETE" } shouldBe false
    }

    test("default DependencyInfo() has projectionComplete=true (backward compat)") {
        // Plain schema-file authors don't set projectionComplete; the
        // default must keep planning unblocked.
        val deps = DependencyInfo(tables = listOf("users"))
        deps.projectionComplete shouldBe true
        deps.tables shouldBe listOf("users")
        deps.columns.shouldBeEmpty()
    }
})
