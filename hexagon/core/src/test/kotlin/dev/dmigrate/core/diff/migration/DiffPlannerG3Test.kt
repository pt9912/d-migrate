package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.ColumnDiff
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Plan §G.3 / §10 L2078-L2083 coverage: the planner splits
 * `ReplaceView` into `DropView` + `CreateView` when the view's
 * `dependencies.columns` references a column that another op in the
 * same migration alters (`DropColumn` / `AlterColumnType` /
 * `AlterColumnNullability`).
 *
 * Split from [DiffPlannerTest] to keep the parent spec under
 * detekt's LargeClass threshold (Konvention: no `@Suppress`).
 */
class DiffPlannerG3Test : FunSpec({

    val planner = DiffPlanner()

    fun usersWith(cols: Map<String, ColumnDefinition>) = TableDefinition(columns = cols)

    fun viewWithColumnDeps(query: String, columns: Map<String, List<String>>) = ViewDefinition(
        query = query,
        dependencies = DependencyInfo(
            tables = columns.keys.toList(),
            columns = columns,
        ),
    )

    test("ReplaceView with column-conflicting DropColumn splits into DropView + CreateView") {
        val before = usersWith(
            mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "legacy" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val after = usersWith(mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        val viewBefore = viewWithColumnDeps(
            "SELECT id, legacy FROM users",
            mapOf("users" to listOf("id", "legacy")),
        )
        val viewAfter = viewWithColumnDeps(
            "SELECT id FROM users",
            mapOf("users" to listOf("id")),
        )
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to before),
            views = mapOf("v_users" to viewBefore),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to after),
            views = mapOf("v_users" to viewAfter),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
            viewsChanged = listOf(
                ViewDiff(
                    name = "v_users",
                    query = ValueChange(viewBefore.query, viewAfter.query),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)

        // ReplaceView is gone; DropView + CreateView present instead.
        result.operations.filterIsInstance<DiffOperation.ReplaceView>().shouldBeEmpty()
        val drop = result.operations.filterIsInstance<DiffOperation.DropView>().single()
        val create = result.operations.filterIsInstance<DiffOperation.CreateView>().single()
        drop.objectRef.path shouldBe listOf("v_users")
        create.objectRef.path shouldBe listOf("v_users")

        val dropColumn = result.operations.filterIsInstance<DiffOperation.DropColumn>().single()

        // DropView -> DropColumn -> CreateView in the sorted order.
        val sorted = result.operations.map { it.id }
        sorted.indexOf(drop.id) shouldBe sorted.indexOf(dropColumn.id) - 1
        sorted.indexOf(dropColumn.id) shouldBe sorted.indexOf(create.id) - 1

        // The drop carries the BEFORE state; the create carries the AFTER state.
        drop.view.query shouldBe viewBefore.query
        create.view.query shouldBe viewAfter.query

        // Dependency edges are wired both ways.
        dropColumn.dependencies shouldContain drop.id
        create.dependencies shouldContain dropColumn.id
    }

    test("ReplaceView with column-conflicting AlterColumnType splits") {
        val before = usersWith(
            mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "email" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val after = usersWith(
            mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "email" to ColumnDefinition(NeutralType.Text(maxLength = 254)),
            ),
        )
        val view = viewWithColumnDeps(
            "SELECT id, email FROM users",
            mapOf("users" to listOf("id", "email")),
        )
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to before),
            views = mapOf("v_users" to view),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to after),
            views = mapOf("v_users" to view.copy(query = "SELECT id, lower(email) FROM users")),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "email",
                            type = ValueChange(NeutralType.Text(), NeutralType.Text(maxLength = 254)),
                        ),
                    ),
                ),
            ),
            viewsChanged = listOf(
                ViewDiff(
                    name = "v_users",
                    query = ValueChange(view.query, "SELECT id, lower(email) FROM users"),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        result.operations.filterIsInstance<DiffOperation.ReplaceView>().shouldBeEmpty()
        result.operations.filterIsInstance<DiffOperation.DropView>() shouldHaveSize 1
        result.operations.filterIsInstance<DiffOperation.CreateView>() shouldHaveSize 1
    }

    test("ReplaceView without column conflict stays ReplaceView (CREATE OR REPLACE path)") {
        val users = usersWith(mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        val viewBefore = viewWithColumnDeps(
            "SELECT id FROM users",
            mapOf("users" to listOf("id")),
        )
        val viewAfter = viewBefore.copy(query = "SELECT id, id AS id2 FROM users")
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to viewBefore),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to users), // No column changes
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
        result.operations.filterIsInstance<DiffOperation.ReplaceView>() shouldHaveSize 1
        result.operations.filterIsInstance<DiffOperation.DropView>().shouldBeEmpty()
        result.operations.filterIsInstance<DiffOperation.CreateView>().shouldBeEmpty()
    }

    test("column conflict on a non-view-referenced column leaves ReplaceView intact") {
        // The view references only `id`; we alter `name`.
        val before = usersWith(
            mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "name" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val after = usersWith(
            mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "name" to ColumnDefinition(NeutralType.Text(maxLength = 100)),
            ),
        )
        val viewBefore = viewWithColumnDeps(
            "SELECT id FROM users",
            mapOf("users" to listOf("id")),
        )
        val viewAfter = viewBefore.copy(query = "SELECT id, id AS alias FROM users")
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to before),
            views = mapOf("v_users" to viewBefore),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to after),
            views = mapOf("v_users" to viewAfter),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "name",
                            type = ValueChange(NeutralType.Text(), NeutralType.Text(maxLength = 100)),
                        ),
                    ),
                ),
            ),
            viewsChanged = listOf(
                ViewDiff(
                    name = "v_users",
                    query = ValueChange(viewBefore.query, viewAfter.query),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        result.operations.filterIsInstance<DiffOperation.ReplaceView>() shouldHaveSize 1
    }

    test("ReplaceView without column-level dependencies is left alone (F.6.b territory)") {
        // The view has table-level deps but NO columns map. The
        // §F.6.b check blocks the column-altering op with
        // VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS; G.3's split is
        // intentionally a no-op here since the planner can't tell
        // which columns the view references.
        val before = usersWith(
            mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "email" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val after = before.copy(columns = before.columns - "email")
        val viewBefore = ViewDefinition(
            query = "SELECT * FROM users",
            dependencies = DependencyInfo(tables = listOf("users")), // table-only, no columns
        )
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to before),
            views = mapOf("v_users" to viewBefore),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to after),
            views = mapOf("v_users" to viewBefore.copy(query = "SELECT id FROM users")),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsRemoved = mapOf("email" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
            viewsChanged = listOf(
                ViewDiff(
                    name = "v_users",
                    query = ValueChange(viewBefore.query, "SELECT id FROM users"),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        // ReplaceView intact (no split); F.6.b blocker fires instead.
        result.operations.filterIsInstance<DiffOperation.ReplaceView>() shouldHaveSize 1
        result.diagnostics.any { it.code == "VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS" } shouldBe true
    }

    test("split ops have deterministic, distinct ids and reference the same view name") {
        val before = usersWith(
            mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "email" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val after = before.copy(columns = before.columns - "email")
        val viewBefore = viewWithColumnDeps(
            "SELECT id, email FROM users",
            mapOf("users" to listOf("id", "email")),
        )
        val viewAfter = viewWithColumnDeps(
            "SELECT id FROM users",
            mapOf("users" to listOf("id")),
        )
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to before),
            views = mapOf("v_users" to viewBefore),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to after),
            views = mapOf("v_users" to viewAfter),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsRemoved = mapOf("email" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
            viewsChanged = listOf(
                ViewDiff(
                    name = "v_users",
                    query = ValueChange(viewBefore.query, viewAfter.query),
                ),
            ),
        )
        val rA = planner.plan(current, desired, diff)
        val rB = planner.plan(current, desired, diff)
        rA.operations.map { it.id } shouldBe rB.operations.map { it.id }

        val drop = rA.operations.filterIsInstance<DiffOperation.DropView>().single()
        val create = rA.operations.filterIsInstance<DiffOperation.CreateView>().single()
        drop.id shouldNotBe create.id
    }
})

private infix fun Set<String>.shouldContain(id: String) {
    if (id !in this) throw AssertionError("Expected dependency set $this to contain $id")
}
