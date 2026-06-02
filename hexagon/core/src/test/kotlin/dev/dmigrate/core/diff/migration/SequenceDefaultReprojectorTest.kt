package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * F.4 Sub-Slice D: column-default `SequenceNextVal` references that
 * point at a renamed sequence must be rewritten to the new name, and
 * the column-bearing op (`CreateTable` / `AddColumn` /
 * `AlterColumnDefault`) must carry a dependency on the corresponding
 * `RenameSequence` so the topological sort orders the rename first.
 *
 * Order-independence: the Mapper emits column-bearing ops in the
 * regular `mapTables` / `mapTableColumns` loops before the rename
 * fold runs. The reprojector therefore runs at the tail of
 * `prepare(...)` and walks the full ops list once.
 */
class SequenceDefaultReprojectorTest : FunSpec({

    val planner = DiffPlanner()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun renameOverlay(from: String, to: String, source: String = "ovl/seq.json"): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "$from->$to",
                    objectType = "sequence",
                    fromName = from,
                    toName = to,
                ),
            ),
            createdAt = "2026-05-19T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()
        return MigrationOverlayDocument(source = source, overlay = overlay)
    }

    fun tableWithSeqDefault(seqName: String) = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(
                type = NeutralType.Integer,
                default = DefaultValue.SequenceNextVal(seqName),
                required = true,
            ),
            "label" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
        ),
        primaryKey = listOf("id"),
    )

    test("CreateTable with column default = SequenceNextVal(renamed) rewrites to the new name") {
        val seq = SequenceDefinition()
        val plan = planner.plan(
            current = emptySchema().copy(sequences = mapOf("old_seq" to seq)),
            desired = emptySchema().copy(
                sequences = mapOf("new_seq" to seq),
                tables = mapOf("orders" to tableWithSeqDefault("old_seq")),
            ),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("new_seq", seq)),
                sequencesRemoved = listOf(NamedSequence("old_seq", seq)),
                tablesAdded = listOf(NamedTable("orders", tableWithSeqDefault("old_seq"))),
            ),
            migrationOverlays = listOf(renameOverlay("old_seq", "new_seq")),
        )

        val createTable = plan.operations.filterIsInstance<DiffOperation.CreateTable>().single()
        val idDefault = createTable.table.columns.getValue("id").default
        idDefault shouldBe DefaultValue.SequenceNextVal("new_seq")

        // Dependency edge to RenameSequence is attached by the analyzer.
        val rename = plan.operations.filterIsInstance<DiffOperation.RenameSequence>().single()
        createTable.dependencies shouldContain rename.id
    }

    test("AddColumn with default = SequenceNextVal(renamed) rewrites + carries the dep") {
        val seq = SequenceDefinition()
        val before = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(), required = true)),
            primaryKey = listOf("id"),
        )
        val after = before.copy(
            columns = before.columns + mapOf(
                "seq_id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal("old_seq"),
                ),
            ),
        )
        val plan = planner.plan(
            current = emptySchema().copy(
                sequences = mapOf("old_seq" to seq),
                tables = mapOf("orders" to before),
            ),
            desired = emptySchema().copy(
                sequences = mapOf("new_seq" to seq),
                tables = mapOf("orders" to after),
            ),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("new_seq", seq)),
                sequencesRemoved = listOf(NamedSequence("old_seq", seq)),
                tablesChanged = listOf(
                    TableDiff(
                        name = "orders",
                        columnsAdded = mapOf("seq_id" to after.columns.getValue("seq_id")),
                    ),
                ),
            ),
            migrationOverlays = listOf(renameOverlay("old_seq", "new_seq")),
        )

        val addColumn = plan.operations.filterIsInstance<DiffOperation.AddColumn>().single()
        addColumn.column.default shouldBe DefaultValue.SequenceNextVal("new_seq")
        val rename = plan.operations.filterIsInstance<DiffOperation.RenameSequence>().single()
        addColumn.dependencies shouldContain rename.id
    }

    test("AlterColumnDefault rewrites SequenceNextVal in both before and after") {
        val seq = SequenceDefinition()
        // Both before and after reference the renamed sequence (the
        // rename is in flight; the after-side might legitimately keep
        // the same default while we update the surrounding schema).
        // The rewrite then makes both sides point at the new name.
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal("old_seq"),
                ),
            ),
            primaryKey = listOf("id"),
        )
        // Change the default value type so the diff actually emits an
        // AlterColumnDefault (a no-op alter would not surface).
        val after = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.NumberLiteral(0),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val plan = planner.plan(
            current = emptySchema().copy(
                sequences = mapOf("old_seq" to seq),
                tables = mapOf("orders" to before),
            ),
            desired = emptySchema().copy(
                sequences = mapOf("new_seq" to seq),
                tables = mapOf("orders" to after),
            ),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("new_seq", seq)),
                sequencesRemoved = listOf(NamedSequence("old_seq", seq)),
                tablesChanged = listOf(
                    TableDiff(
                        name = "orders",
                        columnsChanged = listOf(
                            dev.dmigrate.core.diff.ColumnDiff(
                                name = "id",
                                default = ValueChange(
                                    before = DefaultValue.SequenceNextVal("old_seq"),
                                    after = DefaultValue.NumberLiteral(0),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            migrationOverlays = listOf(renameOverlay("old_seq", "new_seq")),
        )

        val alter = plan.operations.filterIsInstance<DiffOperation.AlterColumnDefault>().single()
        // `before` references the renamed sequence — gets rewritten.
        alter.before shouldBe DefaultValue.SequenceNextVal("new_seq")
        // `after` doesn't reference any renamed sequence, unchanged.
        alter.after shouldBe DefaultValue.NumberLiteral(0)
    }

    test("AlterColumnDefault.after references renamed sequence → dep on RenameSequence") {
        val seq = SequenceDefinition()
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.NumberLiteral(0),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val after = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal("new_seq"),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val plan = planner.plan(
            current = emptySchema().copy(
                sequences = mapOf("old_seq" to seq),
                tables = mapOf("orders" to before),
            ),
            desired = emptySchema().copy(
                sequences = mapOf("new_seq" to seq),
                tables = mapOf("orders" to after),
            ),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("new_seq", seq)),
                sequencesRemoved = listOf(NamedSequence("old_seq", seq)),
                tablesChanged = listOf(
                    TableDiff(
                        name = "orders",
                        columnsChanged = listOf(
                            dev.dmigrate.core.diff.ColumnDiff(
                                name = "id",
                                default = ValueChange(
                                    before = DefaultValue.NumberLiteral(0),
                                    after = DefaultValue.SequenceNextVal("new_seq"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            migrationOverlays = listOf(renameOverlay("old_seq", "new_seq")),
        )

        val alter = plan.operations.filterIsInstance<DiffOperation.AlterColumnDefault>().single()
        val rename = plan.operations.filterIsInstance<DiffOperation.RenameSequence>().single()
        alter.after shouldBe DefaultValue.SequenceNextVal("new_seq")
        alter.dependencies shouldContain rename.id
    }

    test("column default referencing an unrelated sequence is not rewritten and has no dep") {
        val seq = SequenceDefinition()
        val plan = planner.plan(
            current = emptySchema().copy(
                sequences = mapOf("old_seq" to seq, "other_seq" to seq),
            ),
            desired = emptySchema().copy(
                sequences = mapOf("new_seq" to seq, "other_seq" to seq),
                tables = mapOf("orders" to tableWithSeqDefault("other_seq")),
            ),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("new_seq", seq)),
                sequencesRemoved = listOf(NamedSequence("old_seq", seq)),
                tablesAdded = listOf(NamedTable("orders", tableWithSeqDefault("other_seq"))),
            ),
            migrationOverlays = listOf(renameOverlay("old_seq", "new_seq")),
        )

        val createTable = plan.operations.filterIsInstance<DiffOperation.CreateTable>().single()
        createTable.table.columns.getValue("id").default shouldBe DefaultValue.SequenceNextVal("other_seq")
        val rename = plan.operations.filterIsInstance<DiffOperation.RenameSequence>().single()
        // No dep on rename — the column doesn't reference the renamed sequence.
        (rename.id in createTable.dependencies) shouldBe false
    }

    test("multiple renames in one plan all rewrite their respective columns") {
        val seq = SequenceDefinition()
        val table = TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal("seq_a_old"),
                ),
                "b" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal("seq_b_old"),
                ),
            ),
            primaryKey = listOf("a"),
        )
        val plan = planner.plan(
            current = emptySchema().copy(
                sequences = mapOf("seq_a_old" to seq, "seq_b_old" to seq),
            ),
            desired = emptySchema().copy(
                sequences = mapOf("seq_a_new" to seq, "seq_b_new" to seq),
                tables = mapOf("orders" to table),
            ),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(
                    NamedSequence("seq_a_new", seq),
                    NamedSequence("seq_b_new", seq),
                ),
                sequencesRemoved = listOf(
                    NamedSequence("seq_a_old", seq),
                    NamedSequence("seq_b_old", seq),
                ),
                tablesAdded = listOf(NamedTable("orders", table)),
            ),
            migrationOverlays = listOf(
                renameOverlay("seq_a_old", "seq_a_new", source = "ovl/a.json"),
                renameOverlay("seq_b_old", "seq_b_new", source = "ovl/b.json"),
            ),
        )

        val createTable = plan.operations.filterIsInstance<DiffOperation.CreateTable>().single()
        createTable.table.columns.getValue("a").default shouldBe DefaultValue.SequenceNextVal("seq_a_new")
        createTable.table.columns.getValue("b").default shouldBe DefaultValue.SequenceNextVal("seq_b_new")
        val renames = plan.operations.filterIsInstance<DiffOperation.RenameSequence>().associateBy { it.toName }
        createTable.dependencies shouldContain renames.getValue("seq_a_new").id
        createTable.dependencies shouldContain renames.getValue("seq_b_new").id
    }

    test("topological sort places the rename before the column-bearing op (Up direction)") {
        val seq = SequenceDefinition()
        val plan = planner.plan(
            current = emptySchema().copy(sequences = mapOf("old_seq" to seq)),
            desired = emptySchema().copy(
                sequences = mapOf("new_seq" to seq),
                tables = mapOf("orders" to tableWithSeqDefault("old_seq")),
            ),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("new_seq", seq)),
                sequencesRemoved = listOf(NamedSequence("old_seq", seq)),
                tablesAdded = listOf(NamedTable("orders", tableWithSeqDefault("old_seq"))),
            ),
            migrationOverlays = listOf(renameOverlay("old_seq", "new_seq")),
        )

        val renameIdx = plan.operations.indexOfFirst { it is DiffOperation.RenameSequence }
        val createIdx = plan.operations.indexOfFirst { it is DiffOperation.CreateTable }
        // Both ops are present; rename comes first in the topologically
        // sorted Up-direction list so the sequence exists by the time
        // the column default `nextval('new_seq')` evaluates.
        (renameIdx in 0..<createIdx) shouldBe true
    }

    test("no RenameSequence in plan → reprojector is a no-op") {
        val seq = SequenceDefinition()
        val plan = planner.plan(
            current = emptySchema().copy(sequences = mapOf("old_seq" to seq)),
            desired = emptySchema().copy(
                sequences = mapOf("old_seq" to seq),
                tables = mapOf("orders" to tableWithSeqDefault("old_seq")),
            ),
            schemaDiff = SchemaDiff(
                tablesAdded = listOf(NamedTable("orders", tableWithSeqDefault("old_seq"))),
            ),
        )

        val createTable = plan.operations.filterIsInstance<DiffOperation.CreateTable>().single()
        // No rewrite happens because there's no rename to project.
        createTable.table.columns.getValue("id").default shouldBe DefaultValue.SequenceNextVal("old_seq")
    }

    // ── E.3 Sub-Slice D: Drop-Create fallback (MySQL) ────────────

    test("MySQL: CreateTable column default rewrites to new sequence via fallback CreateSequence provenance") {
        // MySQL's `MysqlObjectRenamePolicy` produces
        // `DropCreateFallback` for sequence renames. The Mapper
        // emits `DropSequence(old)` + `CreateSequence(new)` with
        // shared `renameProvenance`. The reprojector picks up the
        // `CreateSequence`'s provenance and rewrites
        // `SequenceNextVal("old_seq")` → `SequenceNextVal("new_seq")`
        // in column defaults exactly as it does for the
        // PostgreSQL-native `RenameSequence` path.
        val seq = SequenceDefinition()
        val plan = planner.plan(
            current = emptySchema().copy(sequences = mapOf("old_seq" to seq)),
            desired = emptySchema().copy(
                sequences = mapOf("new_seq" to seq),
                tables = mapOf("orders" to tableWithSeqDefault("old_seq")),
            ),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("new_seq", seq)),
                sequencesRemoved = listOf(NamedSequence("old_seq", seq)),
                tablesAdded = listOf(NamedTable("orders", tableWithSeqDefault("old_seq"))),
            ),
            migrationOverlays = listOf(renameOverlay("old_seq", "new_seq")),
            capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.MYSQL),
        )

        // No `RenameSequence` op was emitted — the fallback path is
        // active.
        plan.operations.filterIsInstance<DiffOperation.RenameSequence>() shouldBe emptyList()
        val createSeq = plan.operations.filterIsInstance<DiffOperation.CreateSequence>().single()
        createSeq.renameProvenance shouldNotBe null
        createSeq.objectRef.rootName shouldBe "new_seq"

        // CreateTable's column default points at the new name.
        val createTable = plan.operations.filterIsInstance<DiffOperation.CreateTable>().single()
        createTable.table.columns.getValue("id").default shouldBe DefaultValue.SequenceNextVal("new_seq")

        // DependencyAnalyzer already maps `CreateSequence.rootName`
        // → CreateSequence.id, so the column-bearing op carries an
        // edge on the fallback's CreateSequence.
        createTable.dependencies shouldContain createSeq.id
    }

    test("MySQL: AddColumn with fallback rename rewrites default to the new sequence name") {
        val seq = SequenceDefinition()
        val before = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(), required = true)),
            primaryKey = listOf("id"),
        )
        val after = before.copy(
            columns = before.columns + mapOf(
                "seq_id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal("old_seq"),
                ),
            ),
        )
        val plan = planner.plan(
            current = emptySchema().copy(
                sequences = mapOf("old_seq" to seq),
                tables = mapOf("orders" to before),
            ),
            desired = emptySchema().copy(
                sequences = mapOf("new_seq" to seq),
                tables = mapOf("orders" to after),
            ),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("new_seq", seq)),
                sequencesRemoved = listOf(NamedSequence("old_seq", seq)),
                tablesChanged = listOf(
                    TableDiff(
                        name = "orders",
                        columnsAdded = mapOf(
                            "seq_id" to ColumnDefinition(
                                type = NeutralType.Integer,
                                default = DefaultValue.SequenceNextVal("old_seq"),
                            ),
                        ),
                    ),
                ),
            ),
            migrationOverlays = listOf(renameOverlay("old_seq", "new_seq")),
            capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.MYSQL),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameSequence>() shouldBe emptyList()
        val createSeq = plan.operations.filterIsInstance<DiffOperation.CreateSequence>().single()
        val addColumn = plan.operations.filterIsInstance<DiffOperation.AddColumn>().single()
        addColumn.column.default shouldBe DefaultValue.SequenceNextVal("new_seq")
        addColumn.dependencies shouldContain createSeq.id
    }

    test("MySQL: AlterColumnDefault with fallback rename rewrites before+after to the new sequence name") {
        val seq = SequenceDefinition()
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true),
                "seq_id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal("old_seq"),
                ),
            ),
            primaryKey = listOf("id"),
        )
        // After the rename, the desired schema points at new_seq; in
        // the diff we model a change that keeps the column referring
        // to "old_seq" textually so the reprojector has to rewrite
        // both `before` and `after` sides.
        val plan = planner.plan(
            current = emptySchema().copy(
                sequences = mapOf("old_seq" to seq),
                tables = mapOf("orders" to before),
            ),
            desired = emptySchema().copy(
                sequences = mapOf("new_seq" to seq),
                tables = mapOf("orders" to before.copy(
                    columns = before.columns + mapOf(
                        "seq_id" to before.columns.getValue("seq_id").copy(
                            default = DefaultValue.SequenceNextVal("old_seq"),
                        ),
                    ),
                )),
            ),
            schemaDiff = SchemaDiff(
                sequencesAdded = listOf(NamedSequence("new_seq", seq)),
                sequencesRemoved = listOf(NamedSequence("old_seq", seq)),
                tablesChanged = listOf(
                    TableDiff(
                        name = "orders",
                        columnsChanged = listOf(
                            dev.dmigrate.core.diff.ColumnDiff(
                                name = "seq_id",
                                default = ValueChange(
                                    before = DefaultValue.SequenceNextVal("old_seq"),
                                    after = DefaultValue.SequenceNextVal("old_seq"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            migrationOverlays = listOf(renameOverlay("old_seq", "new_seq")),
            capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.MYSQL),
        )

        val alter = plan.operations.filterIsInstance<DiffOperation.AlterColumnDefault>().single()
        alter.before shouldBe DefaultValue.SequenceNextVal("new_seq")
        alter.after shouldBe DefaultValue.SequenceNextVal("new_seq")
    }
})
