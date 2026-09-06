package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Plan-2 §F.4-style rename projection, mirrored from
 * `PostgresDiffRenameTest`: a rename-mapping overlay collapses a matching
 * drop+create (table) or drop+add (column) pair into a native rename.
 * Live-confirmed (Sub-Slice-5a probe, 2026-09-06): Oracle constraint names
 * survive `RENAME TO` unchanged, so — unlike MSSQL's `sp_rename` — there is
 * no follow-up constraint/index rename to render here.
 */
class OracleDiffRenameTest : FunSpec({

    val planner = DiffPlanner()
    val gen = OracleDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun renameOverlay(objectType: String, from: String, to: String): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "oracle",
            entries = listOf(RenameMappingOverlayEntry(id = "$from->$to", objectType = objectType, fromName = from, toName = to)),
            createdAt = "2026-09-06T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()
        return MigrationOverlayDocument(source = "ovl/rename.json", overlay = overlay)
    }

    val table = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "email" to ColumnDefinition(type = NeutralType.Text(maxLength = 200)),
        ),
        primaryKey = listOf("id"),
    )

    test("table rename Up renders ALTER TABLE ... RENAME TO ...") {
        val plan = planner.plan(
            current = emptySchema().copy(tables = mapOf("users_old" to table)),
            desired = emptySchema().copy(tables = mapOf("users" to table)),
            schemaDiff = SchemaDiff(
                tablesAdded = listOf(NamedTable("users", table)),
                tablesRemoved = listOf(NamedTable("users_old", table)),
            ),
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")),
        )
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.size shouldBe 1
        up.statements.single().sql shouldContain "ALTER TABLE \"users_old\" RENAME TO \"users\";"
    }

    test("table rename Down renders the inverse") {
        val plan = planner.plan(
            current = emptySchema().copy(tables = mapOf("users_old" to table)),
            desired = emptySchema().copy(tables = mapOf("users" to table)),
            schemaDiff = SchemaDiff(
                tablesAdded = listOf(NamedTable("users", table)),
                tablesRemoved = listOf(NamedTable("users_old", table)),
            ),
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")),
        )
        val down = gen.generateDown(plan, DdlGenerationOptions())
        down.statements.single().sql shouldContain "ALTER TABLE \"users\" RENAME TO \"users_old\";"
    }

    test("column rename Up renders ALTER TABLE ... RENAME COLUMN ... TO ...") {
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
                "new_name" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
            ),
            primaryKey = listOf("id"),
        )
        val plan = planner.plan(
            current = emptySchema().copy(tables = mapOf("users" to before)),
            desired = emptySchema().copy(tables = mapOf("users" to after)),
            schemaDiff = SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "users",
                        columnsAdded = mapOf("new_name" to after.columns.getValue("new_name")),
                        columnsRemoved = mapOf("old_name" to before.columns.getValue("old_name")),
                    ),
                ),
            ),
            migrationOverlays = listOf(renameOverlay("column", "users.old_name", "users.new_name")),
        )
        val up = gen.generateUp(plan, DdlGenerationOptions())
        up.statements.size shouldBe 1
        up.statements.single().sql shouldContain "ALTER TABLE \"users\" RENAME COLUMN \"old_name\" TO \"new_name\";"
        val down = gen.generateDown(plan, DdlGenerationOptions())
        down.statements.single().sql shouldContain "ALTER TABLE \"users\" RENAME COLUMN \"new_name\" TO \"old_name\";"
    }
})
