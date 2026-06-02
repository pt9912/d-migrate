package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Plan-2 §F.4 dependency-projection T4: pins the mixed-case delta
 * synthesis behaviour. For each rename candidate whose source/target
 * tables (resp. columns) drift intra-object,
 * [RenameIntraObjectDeltaSynthesizer] emits standard operations with
 * `dependencies = setOf(candidate.id)`. The projector folds those
 * deltas into the plan alongside the native `Rename*` so the topo
 * sorter places them strictly after the rename.
 *
 * Split out of [RenameOverlayMapperTest] to keep the LargeClass
 * budget; verifies only the T4-introduced behaviour.
 */
class RenameOverlayMapperT4Test : FunSpec({

    val planner = DiffPlanner()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun renameOverlay(
        objectType: String,
        from: String,
        to: String,
        source: String = "ovl/rename.json",
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
            createdAt = "2026-05-14T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()
        return MigrationOverlayDocument(source = source, overlay = overlay)
    }

    test("column rename with default drift folds to Rename + synthetic AlterColumnDefault") {
        val sharedCols = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
        )
        val beforeCol = ColumnDefinition(
            type = NeutralType.Text(maxLength = 100),
            default = DefaultValue.StringLiteral("legacy"),
        )
        val afterCol = ColumnDefinition(
            type = NeutralType.Text(maxLength = 100),
            default = DefaultValue.StringLiteral("new"),
        )
        val before = TableDefinition(
            columns = sharedCols + mapOf("old_name" to beforeCol),
            primaryKey = listOf("id"),
        )
        val after = TableDefinition(
            columns = sharedCols + mapOf("new_name" to afterCol),
            primaryKey = listOf("id"),
        )
        val current = emptySchema().copy(tables = mapOf("users" to before))
        val desired = emptySchema().copy(tables = mapOf("users" to after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("new_name" to afterCol),
                    columnsRemoved = mapOf("old_name" to beforeCol),
                ),
            ),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("column", "users.old_name", "users.new_name")),
        )

        val rename = plan.operations.filterIsInstance<DiffOperation.RenameColumn>().single()
        val alterDefault = plan.operations.filterIsInstance<DiffOperation.AlterColumnDefault>().single()
        alterDefault.objectRef.path shouldBe listOf("users", "new_name")
        alterDefault.before shouldBe DefaultValue.StringLiteral("legacy")
        alterDefault.after shouldBe DefaultValue.StringLiteral("new")
        alterDefault.dependencies shouldContain rename.id
        plan.diagnostics.map { it.code } shouldNotContain "RENAME_OVERLAY_STRUCTURAL_MISMATCH"
    }

    test("column rename with nullability drift folds to Rename + AlterColumnNullability") {
        val sharedCols = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
        )
        val beforeCol = ColumnDefinition(type = NeutralType.Text(maxLength = 100), required = false)
        val afterCol = ColumnDefinition(type = NeutralType.Text(maxLength = 100), required = true)
        val before = TableDefinition(
            columns = sharedCols + mapOf("old_name" to beforeCol),
            primaryKey = listOf("id"),
        )
        val after = TableDefinition(
            columns = sharedCols + mapOf("new_name" to afterCol),
            primaryKey = listOf("id"),
        )
        val current = emptySchema().copy(tables = mapOf("users" to before))
        val desired = emptySchema().copy(tables = mapOf("users" to after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("new_name" to afterCol),
                    columnsRemoved = mapOf("old_name" to beforeCol),
                ),
            ),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("column", "users.old_name", "users.new_name")),
        )

        val rename = plan.operations.filterIsInstance<DiffOperation.RenameColumn>().single()
        val alterNullability = plan.operations.filterIsInstance<DiffOperation.AlterColumnNullability>().single()
        alterNullability.objectRef.path shouldBe listOf("users", "new_name")
        alterNullability.before shouldBe false
        alterNullability.after shouldBe true
        alterNullability.dependencies shouldContain rename.id
    }

    test("synthetic delta op anchors to the rename even after id disambiguation") {
        // ID stability gate per Plan §4.4: the candidate id and the
        // synthetic delta's `dependencies` reference travel together
        // through `OperationMapper.finalizeIds`. The synthetic op's
        // dependency set MUST point at the operation that ultimately
        // bears the rename id.
        val sharedCols = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
        )
        val beforeCol = ColumnDefinition(type = NeutralType.Text(maxLength = 100))
        val afterCol = ColumnDefinition(type = NeutralType.Text(maxLength = 500))
        val before = TableDefinition(
            columns = sharedCols + mapOf("old_name" to beforeCol),
            primaryKey = listOf("id"),
        )
        val after = TableDefinition(
            columns = sharedCols + mapOf("new_name" to afterCol),
            primaryKey = listOf("id"),
        )
        val current = emptySchema().copy(tables = mapOf("users" to before))
        val desired = emptySchema().copy(tables = mapOf("users" to after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("new_name" to afterCol),
                    columnsRemoved = mapOf("old_name" to beforeCol),
                ),
            ),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("column", "users.old_name", "users.new_name")),
        )

        val rename = plan.operations.filterIsInstance<DiffOperation.RenameColumn>().single()
        val alterType = plan.operations.filterIsInstance<DiffOperation.AlterColumnType>().single()
        alterType.dependencies shouldContain rename.id
        plan.operations.any { it.id == rename.id } shouldBe true
    }

    test("table rename + column add + index drift folds into a single Rename plus synthetic deltas") {
        // Pinning the §4.4 case "Tabellen-Rename + Index-Definition-Drift".
        val baseCols = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "email" to ColumnDefinition(type = NeutralType.Text(maxLength = 200)),
        )
        val before = TableDefinition(
            columns = baseCols,
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(
                    name = "idx_users_email",
                    columns = listOf(IndexColumn("email")),
                    type = IndexType.BTREE,
                    unique = false,
                ),
            ),
        )
        val after = TableDefinition(
            columns = baseCols + mapOf(
                "joined_at" to ColumnDefinition(type = NeutralType.DateTime(timezone = false)),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(
                    name = "idx_users_email",
                    columns = listOf(IndexColumn("email")),
                    type = IndexType.BTREE,
                    unique = true, // drifted
                ),
            ),
        )
        val current = emptySchema().copy(tables = mapOf("users_old" to before))
        val desired = emptySchema().copy(tables = mapOf("users" to after))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", after)),
            tablesRemoved = listOf(NamedTable("users_old", before)),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")),
        )

        plan.operations.filterIsInstance<DiffOperation.CreateTable>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.DropTable>().size shouldBe 0
        val rename = plan.operations.filterIsInstance<DiffOperation.RenameTable>().single()
        val addCol = plan.operations.filterIsInstance<DiffOperation.AddColumn>().single()
        val dropIdx = plan.operations.filterIsInstance<DiffOperation.DropIndex>().single()
        val addIdx = plan.operations.filterIsInstance<DiffOperation.AddIndex>().single()

        addCol.objectRef.path shouldBe listOf("users", "joined_at")
        addCol.dependencies shouldContain rename.id
        dropIdx.dependencies shouldContain rename.id
        addIdx.dependencies shouldContain rename.id

        // Topological sorter must place every delta after the rename.
        val renameIndex = plan.operations.indexOf(rename)
        (plan.operations.indexOf(addCol) > renameIndex) shouldBe true
        (plan.operations.indexOf(addIdx) > renameIndex) shouldBe true
    }

    test("synthetic AlterColumnType triggers the same view-column-dep safety pass as a regular alter") {
        // Plan §4.4 point 3: a column rename whose synthesised
        // AlterColumnType targets a column referenced by a view —
        // with table-level dependency only, no column-level deps —
        // MUST trigger `VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS` the
        // same way a normally-mapped column-alter does. This pins
        // that the safety pass runs on the projection output, not
        // only on the pre-rename mapper output.
        val sharedCols = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
        )
        val beforeCol = ColumnDefinition(type = NeutralType.Text(maxLength = 100))
        val afterCol = ColumnDefinition(type = NeutralType.Text(maxLength = 500))
        val beforeTable = TableDefinition(
            columns = sharedCols + mapOf("old_email" to beforeCol),
            primaryKey = listOf("id"),
        )
        val afterTable = TableDefinition(
            columns = sharedCols + mapOf("new_email" to afterCol),
            primaryKey = listOf("id"),
        )
        // The view declares a TABLE-level dependency on `users`
        // without column-level info. Per `detectViewColumnDepsBlockers`
        // any op altering a column of `users` blocks because the
        // planner cannot prove the view does not reference that
        // column.
        val viewDef = ViewDefinition(
            query = "SELECT 1 FROM users",
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val current = emptySchema().copy(
            tables = mapOf("users" to beforeTable),
            views = mapOf("email_view" to viewDef),
        )
        val desired = emptySchema().copy(
            tables = mapOf("users" to afterTable),
            views = mapOf("email_view" to viewDef),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("new_email" to afterCol),
                    columnsRemoved = mapOf("old_email" to beforeCol),
                ),
            ),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("column", "users.old_email", "users.new_email")),
        )

        // The synthetic AlterColumnType must exist in the final plan…
        plan.operations.filterIsInstance<DiffOperation.AlterColumnType>().size shouldBe 1
        // …and the view-column-dep safety pass must flag it just like
        // a normally-mapped column-alter would.
        plan.diagnostics.map { it.code } shouldContain "VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS"
        plan.hasBlockers shouldBe true
    }
})
