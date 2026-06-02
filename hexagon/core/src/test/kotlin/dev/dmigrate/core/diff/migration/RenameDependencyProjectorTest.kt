package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * F.4 dependency-projection T3: direct tests for
 * [RenameDependencyProjector] + per-dialect
 * [RenameDependencyPolicy] decisions.
 *
 * Acceptance per Plan-2 §F.4 §4.3:
 *
 * - Per dialect at least one AUTOMATIC path + one BLOCKED path.
 * - `DefaultValue.FunctionCall` defaults whose function-name string
 *   references the renamed column's old name conservatively block
 *   across all dialects.
 * - SQLite column renames block unless capabilities are pinned with
 *   `>= 3.26` AND `legacy_alter_table == false` AND a non-FILE_ONLY
 *   source; SQLite table renames are not gated (engine updates the
 *   catalog identity natively).
 */
class RenameDependencyProjectorTest : FunSpec({

    fun simpleTable(extraDefault: DefaultValue? = null): TableDefinition = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "email" to ColumnDefinition(
                type = NeutralType.Text(maxLength = 200),
                default = extraDefault,
            ),
        ),
        primaryKey = listOf("id"),
    )

    fun schemaWith(vararg entries: Pair<String, TableDefinition>) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = entries.toMap(),
    )

    fun tableCandidate(
        id: String = "rename-table-users_old-users",
        from: String = "users_old",
        to: String = "users",
    ) = RenameTableCandidate(
        id = id,
        fromName = from,
        toName = to,
        overlaySource = "ovl/rename.json",
        overlayEntryId = "entry-1",
        overlayHash = "sha256:abc",
        renamable = true,
        structuralDifferences = emptyList(),
        staleReferenceObject = null,
    )

    fun columnCandidate(
        table: String = "users",
        from: String = "email_addr",
        to: String = "email",
    ) = RenameColumnCandidate(
        id = "rename-column-$table-$from-$to",
        tableName = table,
        fromColumn = from,
        toColumn = to,
        overlaySource = "ovl/rename.json",
        overlayEntryId = "entry-c",
        overlayHash = "sha256:def",
        renamable = true,
        structuralDifferences = emptyList(),
        referencingObject = null,
    )

    // ── Foundational projector behaviour (dialect-agnostic) ──────────

    context("foundational behaviour with PostgreSQL policy") {
        val pgCapabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.POSTGRESQL)
        val projector = RenameDependencyProjector(pgCapabilities)

        test("empty items returns the cached empty projection") {
            val diff = SchemaDiff()
            val projection = projector.projectTables(emptyList(), diff, schemaWith(), schemaWith())
            projection.operations.shouldBeEmpty()
            projection.diagnostics.shouldBeEmpty()
            projection.absorbedFromNames.shouldBeEmpty()
            projection.absorbedToNames.shouldBeEmpty()
        }

        test("structurally-equal candidate without policy block emits RenameTable + absorbs both names") {
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            val op = projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameTable>()
            op.id shouldBe "rename-table-users_old-users"
            projection.absorbedFromNames shouldContainExactly setOf("users_old")
            projection.absorbedToNames shouldContainExactly setOf("users")
            projection.diagnostics.shouldBeEmpty()
        }

        test("postRenameDeltaOperations append after the rename in the produced order") {
            val schema = schemaWith("users" to simpleTable())
            val syntheticDelta = DiffOperation.AddColumn(
                id = "add-col-users-email_verified",
                objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("users", "email_verified")),
                column = ColumnDefinition(type = NeutralType.BooleanType),
            )
            val item = RenameTablePlanningItem(
                candidate = tableCandidate(),
                postRenameDeltaOperations = listOf(syntheticDelta),
            )
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.shouldHaveSize(2)
            projection.operations[0].shouldBeInstanceOf<DiffOperation.RenameTable>()
            projection.operations[1] shouldBe syntheticDelta
        }

        test("structural mismatch on table candidate emits RENAME_OVERLAY_STRUCTURAL_MISMATCH") {
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(
                candidate = tableCandidate().copy(
                    renamable = false,
                    structuralDifferences = listOf("removed columns [legacy_id]"),
                ),
            )
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)
            projection.operations.shouldBeEmpty()
            projection.diagnostics.single().code shouldBe RenameOverlayMapper.STRUCTURAL_MISMATCH
        }

        test("staleReferenceObject on table candidate emits RENAME_OVERLAY_DEPENDENCY_PROJECTION_REQUIRED") {
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(
                candidate = tableCandidate().copy(staleReferenceObject = "orders.fk_users"),
            )
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)
            projection.operations.shouldBeEmpty()
            projection.diagnostics.single().code shouldBe RenameOverlayMapper.DEPENDENCY_PROJECTION_REQUIRED
        }

        test("structural mismatch on column candidate emits RENAME_OVERLAY_STRUCTURAL_MISMATCH") {
            val schema = schemaWith("users" to simpleTable())
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(
                candidate = columnCandidate().copy(
                    renamable = false,
                    structuralDifferences = listOf("type Text(200) -> Text(255)"),
                ),
            )
            val projection = projector.projectColumns(listOf(item), table, schema, schema)
            projection.operations.shouldBeEmpty()
            projection.diagnostics.single().code shouldBe RenameOverlayMapper.STRUCTURAL_MISMATCH
        }

        test("referencingObject on column candidate emits RENAME_OVERLAY_DEPENDENCY_PROJECTION_REQUIRED") {
            // Symmetric to the table-side staleReferenceObject case above:
            // when the mapper pre-flags a same-table referencing object on
            // the column candidate, the projector emits the existing
            // mapper-pinned diagnostic without consulting the policy.
            val schema = schemaWith("users" to simpleTable())
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(
                candidate = columnCandidate().copy(referencingObject = "index ix_users_email"),
            )
            val projection = projector.projectColumns(listOf(item), table, schema, schema)
            projection.operations.shouldBeEmpty()
            projection.diagnostics.single().code shouldBe RenameOverlayMapper.DEPENDENCY_PROJECTION_REQUIRED
        }
    }

    // ── Per-dialect AUTOMATIC + BLOCKED matrix ───────────────────────

    context("PostgreSQL policy") {
        val capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.POSTGRESQL)
        val projector = RenameDependencyProjector(capabilities)

        test("AUTOMATIC: column rename in a table with no related FunctionCall defaults folds the rename") {
            // A FunctionCall like `now()` does not reference the renamed
            // column's old name, so the substring-match probe leaves it
            // alone — the rename folds.
            val schema = schemaWith("users" to simpleTable(extraDefault = DefaultValue.FunctionCall("now()")))
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameColumn>()
            projection.absorbedFromColumns shouldContainExactly setOf("email_addr")
            projection.absorbedToColumns shouldContainExactly setOf("email")
        }

        test("BLOCKED: column rename when a FunctionCall default references the old column name") {
            val schema = schemaWith(
                "users" to simpleTable(
                    extraDefault = DefaultValue.FunctionCall("compute_hash(email_addr)"),
                ),
            )
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.shouldBeEmpty()
            val diag = projection.diagnostics.single()
            diag.code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
            diag.severity shouldBe DiffDiagnostic.Severity.WARNING
            diag.message.shouldContain("compute_hash(email_addr)")
            diag.message.shouldContain("email_addr")
        }

        test("AUTOMATIC: table rename never runs the FunctionCall probe") {
            // Defaults like now()/uuid() exist on columns and don't
            // reference the table name. A table rename must not block
            // on the table's column defaults.
            val schema = schemaWith("users" to simpleTable(extraDefault = DefaultValue.FunctionCall("now()")))
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameTable>()
            projection.diagnostics.shouldBeEmpty()
        }
    }

    context("MySQL policy") {
        val capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.MYSQL)
        val projector = RenameDependencyProjector(capabilities)

        test("AUTOMATIC: table rename folds without consulting column defaults") {
            val schema = schemaWith("users" to simpleTable(extraDefault = DefaultValue.FunctionCall("uuid()")))
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameTable>()
            projection.diagnostics.shouldBeEmpty()
        }

        test("BLOCKED: column rename when a FunctionCall default references the old column name") {
            val schema = schemaWith(
                "users" to simpleTable(
                    extraDefault = DefaultValue.FunctionCall("substring_index(email_addr, '@', 1)"),
                ),
            )
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.shouldBeEmpty()
            projection.diagnostics.single().code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
        }
    }

    context("SQLite policy") {
        test("AUTOMATIC: table rename works without pinned capabilities (engine updates the catalog)") {
            val capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.SQLITE)
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameTable>()
            projection.diagnostics.shouldBeEmpty()
        }

        test("BLOCKED: column rename without pinned capabilities") {
            val capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.SQLITE)
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.shouldBeEmpty()
            val diag = projection.diagnostics.single()
            diag.code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
            diag.message.shouldContain("SQLite column-rename propagation through views and triggers requires")
        }

        test("AUTOMATIC: column rename with TEST_PINNED + version >= 3.26 + legacy=false") {
            val capabilities = RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.SQLITE,
                source = RenameCapabilitySource.TEST_PINNED,
                sqliteVersion = "3.27.0",
                sqliteLegacyAlterTable = false,
            )
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameColumn>()
        }

        test("BLOCKED: TEST_PINNED but legacy_alter_table=true still blocks the column rename") {
            val capabilities = RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.SQLITE,
                source = RenameCapabilitySource.TEST_PINNED,
                sqliteVersion = "3.30.0",
                sqliteLegacyAlterTable = true,
            )
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.shouldBeEmpty()
            projection.diagnostics.single().code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
        }

        test("BLOCKED: TEST_PINNED with version < 3.26 still blocks the column rename") {
            val capabilities = RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.SQLITE,
                source = RenameCapabilitySource.TEST_PINNED,
                sqliteVersion = "3.25.999",
                sqliteLegacyAlterTable = false,
            )
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.shouldBeEmpty()
        }

        test("BLOCKED: unparsable SQLite version is treated as unknown capability") {
            val capabilities = RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.SQLITE,
                source = RenameCapabilitySource.TEST_PINNED,
                sqliteVersion = "garbage",
                sqliteLegacyAlterTable = false,
            )
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.shouldBeEmpty()
        }

        test("BLOCKED: pinned capabilities + FunctionCall referencing old name → BOTH blockers fire") {
            val capabilities = RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.SQLITE,
                source = RenameCapabilitySource.FILE_ONLY,
                sqliteVersion = null,
                sqliteLegacyAlterTable = null,
            )
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith(
                "users" to simpleTable(extraDefault = DefaultValue.FunctionCall("hex(email_addr)")),
            )
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.shouldBeEmpty()
            projection.diagnostics.shouldHaveSize(2)
            projection.diagnostics.all { it.code == RENAME_DEPENDENCY_UNPROJECTABLE } shouldBe true
        }
    }

    // ── Policy factory ───────────────────────────────────────────────

    context("RenameDependencyPolicy.forDialect") {
        test("PostgreSQL") {
            RenameDependencyPolicy.forDialect(RenameProjectionDialect.POSTGRESQL)
                .dialect shouldBe RenameProjectionDialect.POSTGRESQL
        }
        test("MySQL") {
            RenameDependencyPolicy.forDialect(RenameProjectionDialect.MYSQL)
                .dialect shouldBe RenameProjectionDialect.MYSQL
        }
        test("SQLite") {
            RenameDependencyPolicy.forDialect(RenameProjectionDialect.SQLITE)
                .dialect shouldBe RenameProjectionDialect.SQLITE
        }
    }

    // ── Version parser regression pins ───────────────────────────────

    context("SQLite capability gating parses versions structurally, not lexicographically") {
        test("3.9.0 is correctly ordered below 3.26.0 — would fail under string comparison") {
            // Pinning §3.2 of the Plan: "Tests muessen insbesondere 3.9 vs. 3.26 … pinnen"
            val capabilities = RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.SQLITE,
                source = RenameCapabilitySource.TEST_PINNED,
                sqliteVersion = "3.9.0",
                sqliteLegacyAlterTable = false,
            )
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.shouldBeEmpty()
            projection.diagnostics.shouldHaveSize(1)
        }
    }
})
