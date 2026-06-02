package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Plan-2 §F.4 dependency-projection T6: pins the
 * [DiffResult.renameProjections] carrier contract. Every overlay-
 * bound rename candidate the planner observes (successful fold or
 * drop+add fallback) MUST appear in the list with stable provenance
 * (overlaySource + overlayEntryId + overlayHash), and the
 * `renameOperationId` discriminates success (non-null, references the
 * emitted op) from fallback (null, accompanied by
 * `fallbackOperationIds` + `fallbackReason`).
 */
class RenameProjectionReportTest : FunSpec({

    val planner = DiffPlanner()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun simpleTable() = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
        ),
        primaryKey = listOf("id"),
    )

    fun renameOverlayDoc(
        entries: List<RenameMappingOverlayEntry>,
        source: String = "ovl/rename.json",
    ): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
            entries = entries,
            createdAt = "2026-05-15T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()
        return MigrationOverlayDocument(source = source, overlay = overlay)
    }

    test("successful fold: report with renameOperationId set + empty fallback") {
        val current = emptySchema().copy(tables = mapOf("users_old" to simpleTable()))
        val desired = emptySchema().copy(tables = mapOf("users" to simpleTable()))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", simpleTable())),
            tablesRemoved = listOf(NamedTable("users_old", simpleTable())),
        )
        val overlay = renameOverlayDoc(
            listOf(
                RenameMappingOverlayEntry(
                    id = "rename-users",
                    objectType = "table",
                    fromName = "users_old",
                    toName = "users",
                ),
            ),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(overlay))

        val rename = plan.operations.filterIsInstance<DiffOperation.RenameTable>().single()
        val report = plan.renameProjections.single()

        report.candidateId shouldBe rename.id
        report.objectType shouldBe "table"
        report.fromPath shouldBe listOf("users_old")
        report.toPath shouldBe listOf("users")
        report.overlaySource shouldBe "ovl/rename.json"
        report.overlayEntryId shouldBe "rename-users"
        report.overlayHash.shouldNotBeNull()
        report.renameOperationId shouldBe rename.id
        report.fallbackOperationIds shouldContainExactly emptyList()
        report.fallbackReason.shouldBeNull()
        report.blockers shouldContainExactly emptyList()
    }

    test("structural mismatch fallback: report with renameOperationId=null + fallbackOperationIds populated") {
        val beforeTable = simpleTable()
        val afterTable = simpleTable().copy(
            columns = simpleTable().columns + mapOf(
                "extra_col" to ColumnDefinition(type = NeutralType.Text(maxLength = 50)),
            ),
            metadata = dev.dmigrate.core.model.TableMetadata(engine = "innodb"),
        )
        val current = emptySchema().copy(tables = mapOf("users_old" to beforeTable))
        val desired = emptySchema().copy(tables = mapOf("users" to afterTable))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", afterTable)),
            tablesRemoved = listOf(NamedTable("users_old", beforeTable)),
        )
        val overlay = renameOverlayDoc(
            listOf(
                RenameMappingOverlayEntry(
                    id = "rename-users",
                    objectType = "table",
                    fromName = "users_old",
                    toName = "users",
                ),
            ),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(overlay))

        // Structural mismatch — projector falls back to drop+create.
        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 0
        val drop = plan.operations.filterIsInstance<DiffOperation.DropTable>().single()
        val create = plan.operations.filterIsInstance<DiffOperation.CreateTable>().single()

        val report = plan.renameProjections.single()
        report.renameOperationId.shouldBeNull()
        report.fallbackOperationIds shouldContain drop.id
        report.fallbackOperationIds shouldContain create.id
        report.fallbackReason.shouldNotBeNull()
    }

    test("multi-entry overlay: each entry yields a distinct report keyed by overlayEntryId") {
        // Pinning §4.6: reports MUST NOT reconstruct entry provenance
        // from `(overlaySource, overlayHash)` because multiple entries
        // share the same hash. Each entry gets its own report keyed
        // by `overlayEntryId`.
        val current = emptySchema().copy(
            tables = mapOf(
                "users_old" to simpleTable(),
                "orders_old" to simpleTable(),
            ),
        )
        val desired = emptySchema().copy(
            tables = mapOf(
                "users" to simpleTable(),
                "orders" to simpleTable(),
            ),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(
                NamedTable("users", simpleTable()),
                NamedTable("orders", simpleTable()),
            ),
            tablesRemoved = listOf(
                NamedTable("users_old", simpleTable()),
                NamedTable("orders_old", simpleTable()),
            ),
        )
        val overlay = renameOverlayDoc(
            listOf(
                RenameMappingOverlayEntry(
                    id = "users-entry",
                    objectType = "table",
                    fromName = "users_old",
                    toName = "users",
                ),
                RenameMappingOverlayEntry(
                    id = "orders-entry",
                    objectType = "table",
                    fromName = "orders_old",
                    toName = "orders",
                ),
            ),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(overlay))

        plan.renameProjections shouldHaveSize 2
        val byEntryId = plan.renameProjections.associateBy { it.overlayEntryId }
        byEntryId.keys shouldContainExactly setOf("users-entry", "orders-entry")
        // Both entries share the same overlayHash (single document)
        // — provenance MUST distinguish via overlayEntryId.
        byEntryId["users-entry"]?.overlayHash shouldBe byEntryId["orders-entry"]?.overlayHash
        byEntryId["users-entry"]?.fromPath shouldBe listOf("users_old")
        byEntryId["orders-entry"]?.fromPath shouldBe listOf("orders_old")
    }

    test("no rename overlay → DiffResult.renameProjections stays empty") {
        // Empty-overlay sanity: a planner run without rename mappings
        // emits no projection reports. Pinning this prevents a future
        // tranche from accidentally emitting empty / placeholder
        // entries.
        val current = emptySchema().copy(tables = mapOf("users" to simpleTable()))
        val desired = emptySchema().copy(tables = mapOf("users" to simpleTable()))
        val diff = SchemaDiff()
        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff)
        plan.renameProjections shouldBe emptyList()
    }

    test("policy-blocker fallback: report carries the blocker list + non-null fallbackReason") {
        // T3/T5 path: the projector blocks on a `DefaultValue.FunctionCall`
        // referencing the renamed column's old name. The candidate
        // falls back to drop+add; the report MUST carry the policy
        // blocker plus the renderable fallback ids.
        val sharedCols = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
        )
        val beforeCol = ColumnDefinition(type = NeutralType.Text(maxLength = 100))
        val afterCol = ColumnDefinition(type = NeutralType.Text(maxLength = 100))
        val before = TableDefinition(
            columns = sharedCols + mapOf(
                "old_name" to beforeCol,
                "fingerprint" to ColumnDefinition(
                    type = NeutralType.Text(maxLength = 64),
                    default = dev.dmigrate.core.model.DefaultValue.FunctionCall("md5(old_name)"),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val after = TableDefinition(
            columns = sharedCols + mapOf(
                "new_name" to afterCol,
                "fingerprint" to ColumnDefinition(
                    type = NeutralType.Text(maxLength = 64),
                    default = dev.dmigrate.core.model.DefaultValue.FunctionCall("md5(old_name)"),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val current = emptySchema().copy(tables = mapOf("users" to before))
        val desired = emptySchema().copy(tables = mapOf("users" to after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                dev.dmigrate.core.diff.TableDiff(
                    name = "users",
                    columnsAdded = mapOf("new_name" to afterCol),
                    columnsRemoved = mapOf("old_name" to beforeCol),
                ),
            ),
        )
        val overlay = renameOverlayDoc(
            listOf(
                RenameMappingOverlayEntry(
                    id = "rename-column-name",
                    objectType = "column",
                    fromName = "users.old_name",
                    toName = "users.new_name",
                ),
            ),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(overlay))

        plan.operations.filterIsInstance<DiffOperation.RenameColumn>().size shouldBe 0
        val report = plan.renameProjections.single()
        report.renameOperationId.shouldBeNull()
        report.fallbackOperationIds shouldHaveSize 2
        report.blockers.shouldNotBeNull()
        report.blockers shouldHaveSize 1
        report.blockers.single().code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
        report.fallbackReason.shouldNotBeNull()
    }

    test("RenameTable / RenameColumn ops carry overlayEntryId — entry provenance survives folding") {
        val current = emptySchema().copy(tables = mapOf("users_old" to simpleTable()))
        val desired = emptySchema().copy(tables = mapOf("users" to simpleTable()))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", simpleTable())),
            tablesRemoved = listOf(NamedTable("users_old", simpleTable())),
        )
        val overlay = renameOverlayDoc(
            listOf(
                RenameMappingOverlayEntry(
                    id = "the-only-entry",
                    objectType = "table",
                    fromName = "users_old",
                    toName = "users",
                ),
            ),
        )

        val plan = planner.plan(current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(overlay))

        val rename = plan.operations.filterIsInstance<DiffOperation.RenameTable>().single()
        rename.overlayEntryId shouldBe "the-only-entry"
        plan.renameProjections.single().overlayEntryId shouldBe "the-only-entry"
    }
})
