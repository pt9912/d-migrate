package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Plan-2 §F.4 second slice: verifies that the [OperationMapper]
 * collapses matching `(DropTable, CreateTable)` and per-table
 * `(DropColumn, AddColumn)` pairs into [DiffOperation.RenameTable] /
 * [DiffOperation.RenameColumn] when an active rename overlay is
 * supplied — and falls back to drop+add with a warning when source
 * and target diverge structurally.
 */
class RenameOverlayMapperTest : FunSpec({

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
        source: String = "ovl/rename.json",
        sourceFingerprint: String = "src-fp",
        targetFingerprint: String = "dst-fp",
        dialect: String = "postgresql",
    ): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = sourceFingerprint,
            targetFingerprint = targetFingerprint,
            dialect = dialect,
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

    test("table rename overlay collapses Drop+Create into RenameTable with overlay metadata") {
        val before = simpleTable()
        val after = simpleTable()
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
        val renames = plan.operations.filterIsInstance<DiffOperation.RenameTable>()
        renames.size shouldBe 1
        renames.single().fromName shouldBe "users_old"
        renames.single().toName shouldBe "users"
        renames.single().overlaySource shouldBe "ovl/rename.json"
        renames.single().overlayHash?.length shouldBe 64
    }

    test("table rename overlay with structurally different source emits warning and keeps Drop+Add") {
        val before = simpleTable()
        val after = simpleTable().copy(
            columns = simpleTable().columns + mapOf(
                "joined_at" to ColumnDefinition(type = NeutralType.DateTime(timezone = false)),
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

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.CreateTable>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropTable>().size shouldBe 1
        val mismatchDiagnostic = plan.diagnostics.single { it.code == "RENAME_OVERLAY_STRUCTURAL_MISMATCH" }
        mismatchDiagnostic.message shouldContainStr "added columns [joined_at]"
    }

    test("column rename overlay collapses per-table Drop+Add into RenameColumn") {
        val sharedCols = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
        )
        val before = TableDefinition(
            columns = sharedCols + mapOf(
                "old_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
            ),
            primaryKey = listOf("id"),
        )
        val after = TableDefinition(
            columns = sharedCols + mapOf(
                "new_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
            ),
            primaryKey = listOf("id"),
        )
        val current = emptySchema().copy(tables = mapOf("users" to before))
        val desired = emptySchema().copy(tables = mapOf("users" to after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf(
                        "new_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
                    ),
                    columnsRemoved = mapOf(
                        "old_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
                    ),
                ),
            ),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("column", "users.old_name", "users.new_name")),
        )

        plan.operations.filterIsInstance<DiffOperation.AddColumn>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.DropColumn>().size shouldBe 0
        val renames = plan.operations.filterIsInstance<DiffOperation.RenameColumn>()
        renames.size shouldBe 1
        renames.single().fromName shouldBe "old_name"
        renames.single().toName shouldBe "new_name"
        renames.single().objectRef.path shouldBe listOf("users", "new_name")
    }

    test("column rename overlay with type drift emits warning and keeps Drop+Add") {
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "old_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
            ),
            primaryKey = listOf("id"),
        )
        val after = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "new_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 500)),
            ),
            primaryKey = listOf("id"),
        )
        val current = emptySchema().copy(tables = mapOf("users" to before))
        val desired = emptySchema().copy(tables = mapOf("users" to after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf(
                        "new_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 500)),
                    ),
                    columnsRemoved = mapOf(
                        "old_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
                    ),
                ),
            ),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("column", "users.old_name", "users.new_name")),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameColumn>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.AddColumn>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropColumn>().size shouldBe 1
        val mismatchDiagnostic = plan.diagnostics.single { it.code == "RENAME_OVERLAY_STRUCTURAL_MISMATCH" }
        mismatchDiagnostic.message shouldContainStr "type"
    }

    test("unqualified column rename mapping fans out across matching tables") {
        val schema = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "created" to ColumnDefinition(type = NeutralType.DateTime(timezone = false)),
            ),
            primaryKey = listOf("id"),
        )
        val schemaAfter = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "created_at" to ColumnDefinition(type = NeutralType.DateTime(timezone = false)),
            ),
            primaryKey = listOf("id"),
        )
        val current = emptySchema().copy(tables = mapOf("users" to schema, "orders" to schema))
        val desired = emptySchema().copy(tables = mapOf("users" to schemaAfter, "orders" to schemaAfter))
        val tableDiff = TableDiff(
            name = "users",
            columnsAdded = mapOf("created_at" to schemaAfter.columns["created_at"]!!),
            columnsRemoved = mapOf("created" to schema.columns["created"]!!),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(tableDiff, tableDiff.copy(name = "orders")),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("column", "created", "created_at")),
        )

        val renames = plan.operations.filterIsInstance<DiffOperation.RenameColumn>()
        renames.size shouldBe 2
        renames.map { it.objectRef.path[0] }.toSet() shouldBe setOf("users", "orders")
    }

    test("column rename is blocked when an index on the same table references the column") {
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "old_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(
                    name = "idx_users_old_name",
                    columns = listOf(IndexColumn("old_name")),
                    type = IndexType.BTREE,
                    unique = false,
                ),
            ),
        )
        val after = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "new_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(
                    name = "idx_users_old_name",
                    columns = listOf(IndexColumn("new_name")),
                    type = IndexType.BTREE,
                    unique = false,
                ),
            ),
        )
        val current = emptySchema().copy(tables = mapOf("users" to before))
        val desired = emptySchema().copy(tables = mapOf("users" to after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("new_name" to after.columns["new_name"]!!),
                    columnsRemoved = mapOf("old_name" to before.columns["old_name"]!!),
                    indicesChanged = listOf(
                        ValueChange(before.indices[0], after.indices[0]),
                    ),
                ),
            ),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("column", "users.old_name", "users.new_name")),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameColumn>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.AddColumn>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropColumn>().size shouldBe 1
        plan.diagnostics.map { it.code } shouldContain "RENAME_OVERLAY_DEPENDENCY_PROJECTION_REQUIRED"
    }
    test("cross-table column rename mapping is rejected with a warning") {
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "old_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
            ),
            primaryKey = listOf("id"),
        )
        val after = before
        val current = emptySchema().copy(tables = mapOf("users" to before, "orders" to before))
        val desired = emptySchema().copy(tables = mapOf("users" to after, "orders" to after))
        val diff = SchemaDiff()

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("column", "users.old_name", "orders.new_name")),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameColumn>().size shouldBe 0
        plan.diagnostics.map { it.code } shouldContain "RENAME_OVERLAY_CROSS_TABLE_REJECTED"
    }

    test("mixed-qualification column rename mapping is rejected with a warning") {
        val plan = planner.plan(
            current = emptySchema(),
            desired = emptySchema(),
            schemaDiff = SchemaDiff(),
            migrationOverlays = listOf(renameOverlay("column", "users.old_name", "new_name")),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameColumn>().size shouldBe 0
        plan.diagnostics.map { it.code } shouldContain "RENAME_OVERLAY_MIXED_COLUMN_QUALIFICATION"
    }

    test("table rename is rejected when a same-named index changes its column set") {
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "email" to ColumnDefinition(type = NeutralType.Text(maxLength = 200)),
                "username" to ColumnDefinition(type = NeutralType.Text(maxLength = 200)),
            ),
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
            columns = before.columns,
            primaryKey = before.primaryKey,
            indices = listOf(
                IndexDefinition(
                    name = "idx_users_email",
                    columns = listOf(IndexColumn("username")),
                    type = IndexType.BTREE,
                    unique = false,
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

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.CreateTable>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropTable>().size shouldBe 1
        val mismatch = plan.diagnostics.single { it.code == "RENAME_OVERLAY_STRUCTURAL_MISMATCH" }
        mismatch.message shouldContainStr "index 'idx_users_email' definition changed"
    }

    test("table rename is rejected when an anonymous index swaps its columns") {
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "email" to ColumnDefinition(type = NeutralType.Text(maxLength = 200)),
                "username" to ColumnDefinition(type = NeutralType.Text(maxLength = 200)),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(
                    name = null,
                    columns = listOf(IndexColumn("email")),
                    type = IndexType.BTREE,
                    unique = false,
                ),
            ),
        )
        val after = TableDefinition(
            columns = before.columns,
            primaryKey = before.primaryKey,
            indices = listOf(
                IndexDefinition(
                    name = null,
                    columns = listOf(IndexColumn("username")),
                    type = IndexType.BTREE,
                    unique = false,
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

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.CreateTable>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropTable>().size shouldBe 1
        val mismatch = plan.diagnostics.single { it.code == "RENAME_OVERLAY_STRUCTURAL_MISMATCH" }
        // Anonymous indices keyed by their canonical payload — both sides
        // surface separately in the message.
        mismatch.message shouldContainStr "removed indices"
        mismatch.message shouldContainStr "added indices"
    }

    test("duplicate rename mappings collapse into a single RenameTable op") {
        val before = simpleTable()
        val after = simpleTable()
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
            migrationOverlays = listOf(
                renameOverlay("table", "users_old", "users", source = "ovl/a.json"),
                renameOverlay("table", "users_old", "users", source = "ovl/b.json"),
            ),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 1
    }

    test("no overlay leaves drop/add untouched") {
        val before = simpleTable()
        val after = simpleTable()
        val current = emptySchema().copy(tables = mapOf("users_old" to before))
        val desired = emptySchema().copy(tables = mapOf("users" to after))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", after)),
            tablesRemoved = listOf(NamedTable("users_old", before)),
        )

        val plan = planner.plan(current, desired, diff)

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.CreateTable>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropTable>().size shouldBe 1
    }
})
