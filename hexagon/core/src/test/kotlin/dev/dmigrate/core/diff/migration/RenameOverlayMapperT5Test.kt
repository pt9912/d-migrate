package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Plan-2 §F.4 dependency-projection T5: pins the explicit view-
 * reprojection behaviour. A table rename whose schema also contains a
 * view declaring a table-level dependency on the renamed object
 * triggers a `DropView` + `CreateView` pair against the desired view
 * body, anchored to the rename via `dependencies = setOf(rename.id)`.
 * When the desired schema does not carry the view (or carries an
 * empty body), the projector emits a `RENAME_DEPENDENCY_UNPROJECTABLE`
 * blocker and falls back to drop+create.
 *
 * Split out of [RenameOverlayMapperTest] to keep the LargeClass
 * budget.
 */
class RenameOverlayMapperT5Test : FunSpec({

    val planner = DiffPlanner()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun simpleTable() = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "email" to ColumnDefinition(type = NeutralType.Text(maxLength = 200)),
        ),
        primaryKey = listOf("id"),
    )

    fun renameOverlay(
        objectType: String,
        from: String,
        to: String,
    ): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "$from-to-$to",
                    objectType = objectType,
                    fromName = from,
                    toName = to,
                ),
            ),
            createdAt = "2026-05-15T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()
        return MigrationOverlayDocument(source = "ovl/rename.json", overlay = overlay)
    }

    test("PostgreSQL: view referencing renamed table folds into Rename + explicit DropView + CreateView") {
        val currentView = ViewDefinition(
            query = "SELECT id, email FROM users_old",
            dependencies = DependencyInfo(tables = listOf("users_old")),
        )
        val desiredView = ViewDefinition(
            query = "SELECT id, email FROM users",
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val current = emptySchema().copy(
            tables = mapOf("users_old" to simpleTable()),
            views = mapOf("email_view" to currentView),
        )
        val desired = emptySchema().copy(
            tables = mapOf("users" to simpleTable()),
            views = mapOf("email_view" to desiredView),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", simpleTable())),
            tablesRemoved = listOf(NamedTable("users_old", simpleTable())),
            viewsChanged = listOf(
                ViewDiff(
                    name = "email_view",
                    query = ValueChange(
                        "SELECT id, email FROM users_old",
                        "SELECT id, email FROM users",
                    ),
                ),
            ),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")))

        val rename = plan.operations.filterIsInstance<DiffOperation.RenameTable>().single()
        val drop = plan.operations.filterIsInstance<DiffOperation.DropView>().single()
        val create = plan.operations.filterIsInstance<DiffOperation.CreateView>().single()

        // Explicit Drop+Create against the desired body, anchored to the rename.
        drop.objectRef.path shouldBe listOf("email_view")
        drop.dependencies shouldContain rename.id
        create.objectRef.path shouldBe listOf("email_view")
        create.view.query shouldBe "SELECT id, email FROM users"
        create.dependencies shouldContain rename.id
        create.dependencies shouldContain drop.id

        // The regular mapper MUST NOT emit a duplicate ReplaceView
        // for the absorbed view.
        plan.operations.filterIsInstance<DiffOperation.ReplaceView>()
            .none { it.objectRef.path == listOf("email_view") } shouldBe true

        // Topological sorter must place the drop+create after the rename.
        val renameIdx = plan.operations.indexOf(rename)
        (plan.operations.indexOf(drop) > renameIdx) shouldBe true
        (plan.operations.indexOf(create) > plan.operations.indexOf(drop)) shouldBe true
    }

    test("MySQL: same shape (explicit DropView + CreateView) for table rename with dependent view") {
        // MySQL never rewrites view bodies during `RENAME TABLE`, so
        // the projector's explicit Drop+Create is the only safe path.
        // The dialect is encoded in `renameOverlay` for the overlay's
        // `dialect` field; the planner test uses dialect-agnostic
        // capabilities (DiffPlanner default = PostgreSQL policy), but
        // T5 behaviour is identical across PG/MySQL — the policy
        // routes to the same `RenameViewReprojector`. A renderer-
        // level test (not here) pins the dialect-specific SQL.
        val currentView = ViewDefinition(
            query = "SELECT id FROM orders_old",
            dependencies = DependencyInfo(tables = listOf("orders_old")),
        )
        val desiredView = ViewDefinition(
            query = "SELECT id FROM orders",
            dependencies = DependencyInfo(tables = listOf("orders")),
        )
        val current = emptySchema().copy(
            tables = mapOf("orders_old" to simpleTable()),
            views = mapOf("order_view" to currentView),
        )
        val desired = emptySchema().copy(
            tables = mapOf("orders" to simpleTable()),
            views = mapOf("order_view" to desiredView),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("orders", simpleTable())),
            tablesRemoved = listOf(NamedTable("orders_old", simpleTable())),
            viewsChanged = listOf(
                ViewDiff(
                    name = "order_view",
                    query = ValueChange(
                        "SELECT id FROM orders_old",
                        "SELECT id FROM orders",
                    ),
                ),
            ),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "orders_old", "orders")))

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropView>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.CreateView>().single().view.query shouldBe
            "SELECT id FROM orders"
        plan.operations.filterIsInstance<DiffOperation.ReplaceView>().size shouldBe 0
    }

    test("BLOCKED: view missing in desired schema → RENAME_DEPENDENCY_UNPROJECTABLE, table falls back to drop+create") {
        val currentView = ViewDefinition(
            query = "SELECT id FROM users_old",
            dependencies = DependencyInfo(tables = listOf("users_old")),
        )
        val current = emptySchema().copy(
            tables = mapOf("users_old" to simpleTable()),
            views = mapOf("legacy_view" to currentView),
        )
        // Note: legacy_view dropped from desired (operator intends to remove it).
        val desired = emptySchema().copy(tables = mapOf("users" to simpleTable()))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", simpleTable())),
            tablesRemoved = listOf(NamedTable("users_old", simpleTable())),
            viewsRemoved = listOf(NamedView("legacy_view", currentView)),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")))

        // Projector blocks → table rename does NOT fold; falls back to drop+create.
        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.CreateTable>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropTable>().size shouldBe 1

        val diag = plan.diagnostics.single { it.code == RENAME_DEPENDENCY_UNPROJECTABLE }
        diag.severity shouldBe DiffDiagnostic.Severity.WARNING
        diag.message.shouldContain("legacy_view")
        diag.message.shouldContain("users_old")
    }

    test("BLOCKED: desired view body is empty → fall back with RENAME_DEPENDENCY_UNPROJECTABLE") {
        val currentView = ViewDefinition(
            query = "SELECT id FROM users_old",
            dependencies = DependencyInfo(tables = listOf("users_old")),
        )
        val desiredView = ViewDefinition(
            query = null, // empty body → cannot reproject
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val current = emptySchema().copy(
            tables = mapOf("users_old" to simpleTable()),
            views = mapOf("email_view" to currentView),
        )
        val desired = emptySchema().copy(
            tables = mapOf("users" to simpleTable()),
            views = mapOf("email_view" to desiredView),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", simpleTable())),
            tablesRemoved = listOf(NamedTable("users_old", simpleTable())),
            viewsChanged = listOf(
                ViewDiff(
                    name = "email_view",
                    query = ValueChange(
                        "SELECT id, email FROM users_old",
                        "SELECT id, email FROM users",
                    ),
                ),
            ),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")))

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.DropView>().none {
            it.objectRef.path == listOf("email_view") && it.dependencies.isNotEmpty()
        } shouldBe true
        plan.diagnostics.map { it.code } shouldContain RENAME_DEPENDENCY_UNPROJECTABLE
    }

    test("view without table-level dependency on the renamed table is NOT reprojected") {
        // Carve-out: only views that declare a table-level dependency
        // on the renamed table are reprojected. A view unrelated to
        // the renamed table stays untouched.
        val currentView = ViewDefinition(
            query = "SELECT id FROM elsewhere",
            dependencies = DependencyInfo(tables = listOf("elsewhere")),
        )
        val desiredView = currentView
        val elsewhere = simpleTable()
        val current = emptySchema().copy(
            tables = mapOf("users_old" to simpleTable(), "elsewhere" to elsewhere),
            views = mapOf("unrelated_view" to currentView),
        )
        val desired = emptySchema().copy(
            tables = mapOf("users" to simpleTable(), "elsewhere" to elsewhere),
            views = mapOf("unrelated_view" to desiredView),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", simpleTable())),
            tablesRemoved = listOf(NamedTable("users_old", simpleTable())),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")))

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropView>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.CreateView>().size shouldBe 0
    }

    test("probe matches `fromName` only — a current view declaring deps on `toName` is NOT reprojected") {
        // Defensive: a pre-rename current schema declaring a
        // dependency on the POST-rename name is either stale
        // provenance or catalog noise. The reprojector must not pick
        // it up; otherwise unrelated views get dragged into the
        // rename's Drop+Create pipeline.
        val currentView = ViewDefinition(
            query = "SELECT id FROM users",
            // Note: deps on the NEW name in the CURRENT schema —
            // suspicious provenance.
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val current = emptySchema().copy(
            tables = mapOf("users_old" to simpleTable()),
            views = mapOf("forward_view" to currentView),
        )
        val desired = emptySchema().copy(
            tables = mapOf("users" to simpleTable()),
            views = mapOf("forward_view" to currentView),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", simpleTable())),
            tablesRemoved = listOf(NamedTable("users_old", simpleTable())),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")))

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 1
        // No view-side reprojection — forward_view stays untouched.
        plan.operations.filterIsInstance<DiffOperation.DropView>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.CreateView>().size shouldBe 0
    }

    test("triggers stay NO_PROJECTION_AVAILABLE — opaque body, no reprojection") {
        // Plan §3.7 / §4.5 carve-out: trigger bodies are opaque
        // strings in the neutral model, so even a trigger declaring a
        // table-level dep on the renamed table is not reprojected by
        // T5. The carve-out is load-bearing — without this test, a
        // future implementation that adds trigger reprojection would
        // silently slip past CI.
        val trigger = dev.dmigrate.core.model.TriggerDefinition(
            table = "users_old",
            event = dev.dmigrate.core.model.TriggerEvent.INSERT,
            timing = dev.dmigrate.core.model.TriggerTiming.AFTER,
            body = "BEGIN INSERT INTO audit VALUES (NEW.id); END",
            dependencies = DependencyInfo(tables = listOf("users_old")),
        )
        val current = emptySchema().copy(
            tables = mapOf("users_old" to simpleTable()),
            triggers = mapOf("audit_trigger" to trigger),
        )
        val desired = emptySchema().copy(
            tables = mapOf("users" to simpleTable()),
            triggers = mapOf("audit_trigger" to trigger.copy(table = "users")),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", simpleTable())),
            tablesRemoved = listOf(NamedTable("users_old", simpleTable())),
            triggersChanged = listOf(
                dev.dmigrate.core.diff.TriggerDiff(
                    name = "audit_trigger",
                    table = ValueChange("users_old", "users"),
                ),
            ),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")))

        // T5 does not synthesise trigger reprojection — the regular
        // mapper's `mapTriggers` handles `triggersChanged` via
        // `ReplaceTrigger`. The rename still folds.
        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 1
        // No view-side DropView/CreateView (no view in this scenario).
        plan.operations.filterIsInstance<DiffOperation.DropView>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.CreateView>().size shouldBe 0
    }

    test("G.3 no-op: projector emits DropView+CreateView (not ReplaceView), so splitReplaceViewsForColumnConflicts has nothing to split") {
        // Plan §4.5 point 3 demands a test that pins the
        // splitReplaceViewsForColumnConflicts interaction. T5's
        // projector emits `DropView` + `CreateView` directly, so the
        // splitter (which inspects only `ReplaceView`) is a no-op for
        // these ops. Pinning this invariant means a future tranche
        // that switches the projector to `ReplaceView` cannot land
        // without also handling the split.
        val currentView = ViewDefinition(
            query = "SELECT id FROM users_old",
            dependencies = DependencyInfo(tables = listOf("users_old")),
        )
        val desiredView = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val current = emptySchema().copy(
            tables = mapOf("users_old" to simpleTable()),
            views = mapOf("email_view" to currentView),
        )
        val desired = emptySchema().copy(
            tables = mapOf("users" to simpleTable()),
            views = mapOf("email_view" to desiredView),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", simpleTable())),
            tablesRemoved = listOf(NamedTable("users_old", simpleTable())),
            viewsChanged = listOf(
                ViewDiff(
                    name = "email_view",
                    query = ValueChange("SELECT id FROM users_old", "SELECT id FROM users"),
                ),
            ),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")))

        // The projector's explicit ops are Drop+Create — NOT ReplaceView.
        plan.operations.filterIsInstance<DiffOperation.ReplaceView>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.DropView>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.CreateView>().size shouldBe 1
        // No `::g3-split` synthetic id artifact — the splitter did not
        // run on the projector's ops.
        plan.operations.none { it.id.contains("::g3-split") } shouldBe true
    }

    test("RenameViewReprojector unit: emits ordered Drop→Create with proper dependency chain") {
        val candidate = RenameTableCandidate(
            id = "rename-table-A-B",
            fromName = "A",
            toName = "B",
            overlaySource = "ovl",
            overlayEntryId = "e",
            overlayHash = null,
            renamable = true,
            structuralDifferences = emptyList(),
            staleReferenceObject = null,
        )
        val currentView = ViewDefinition(
            query = "SELECT * FROM A",
            dependencies = DependencyInfo(tables = listOf("A")),
        )
        val desiredView = ViewDefinition(
            query = "SELECT * FROM B",
            dependencies = DependencyInfo(tables = listOf("B")),
        )
        val current = SchemaDefinition(name = "S", version = "1",
            tables = mapOf("A" to simpleTable()),
            views = mapOf("v" to currentView))
        val desired = SchemaDefinition(name = "S", version = "1",
            tables = mapOf("B" to simpleTable()),
            views = mapOf("v" to desiredView))

        val outcome = RenameViewReprojector.reprojectViewsForTableRename(candidate, current, desired)

        outcome.absorbedViews shouldBe setOf("v")
        outcome.blockers.size shouldBe 0
        outcome.operations.size shouldBe 2
        val drop = outcome.operations[0]
        val create = outcome.operations[1]
        (drop is DiffOperation.DropView) shouldBe true
        (create is DiffOperation.CreateView) shouldBe true
        drop.dependencies shouldBe setOf(candidate.id)
        // Create depends on BOTH the rename AND the preceding drop —
        // the topo sorter places Drop→Create deterministically.
        create.dependencies shouldBe setOf(candidate.id, drop.id)
    }
})
