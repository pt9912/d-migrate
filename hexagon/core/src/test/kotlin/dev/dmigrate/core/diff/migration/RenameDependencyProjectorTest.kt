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
 * - `DefaultValue.FunctionCall` in the rename environment blocks
 *   conservatively across all dialects.
 * - SQLite blocks unless capabilities are pinned with `>= 3.26` AND
 *   `legacy_alter_table == false` AND a non-FILE_ONLY source.
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
        structurallyEqual = true,
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
        structurallyEqual = true,
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

        test("structural mismatch emits RENAME_OVERLAY_STRUCTURAL_MISMATCH, not policy check") {
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(
                candidate = tableCandidate().copy(
                    structurallyEqual = false,
                    structuralDifferences = listOf("removed columns [legacy_id]"),
                ),
            )
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)
            projection.operations.shouldBeEmpty()
            projection.diagnostics.single().code shouldBe RenameOverlayMapper.STRUCTURAL_MISMATCH
        }

        test("staleReferenceObject emits RENAME_OVERLAY_DEPENDENCY_PROJECTION_REQUIRED, not policy check") {
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(
                candidate = tableCandidate().copy(staleReferenceObject = "orders.fk_users"),
            )
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)
            projection.operations.shouldBeEmpty()
            projection.diagnostics.single().code shouldBe RenameOverlayMapper.DEPENDENCY_PROJECTION_REQUIRED
        }
    }

    // ── Per-dialect AUTOMATIC + BLOCKED matrix ───────────────────────

    context("PostgreSQL policy") {
        val capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.POSTGRESQL)
        val projector = RenameDependencyProjector(capabilities)

        test("AUTOMATIC: column rename in a table without function-call defaults folds the rename") {
            val schema = schemaWith("users" to simpleTable())
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameColumn>()
            projection.absorbedFromColumns shouldContainExactly setOf("email_addr")
            projection.absorbedToColumns shouldContainExactly setOf("email")
        }

        test("BLOCKED: column rename in a table that also has a FunctionCall default is rejected") {
            val schema = schemaWith("users" to simpleTable(extraDefault = DefaultValue.FunctionCall("now()")))
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.shouldBeEmpty()
            val diag = projection.diagnostics.single()
            diag.code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
            diag.severity shouldBe DiffDiagnostic.Severity.WARNING
            diag.message.shouldContain("DefaultValue.FunctionCall")
            diag.message.shouldContain("now()")
        }
    }

    context("MySQL policy") {
        val capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.MYSQL)
        val projector = RenameDependencyProjector(capabilities)

        test("AUTOMATIC: table rename without function-call defaults folds the rename") {
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameTable>()
            projection.absorbedToNames shouldContainExactly setOf("users")
        }

        test("BLOCKED: column rename in a table with a FunctionCall default is rejected") {
            val schema = schemaWith(
                "users" to simpleTable(extraDefault = DefaultValue.FunctionCall("uuid()")),
            )
            val table = TableDiff(name = "users")
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = projector.projectColumns(listOf(item), table, schema, schema)

            projection.operations.shouldBeEmpty()
            projection.diagnostics.single().code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
        }
    }

    context("SQLite policy") {
        test("BLOCKED: FILE_ONLY without pinned capabilities blocks even simple renames") {
            val capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.SQLITE)
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.shouldBeEmpty()
            val diag = projection.diagnostics.single()
            diag.code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
            diag.message.shouldContain("SQLite rename-dependency projection requires pinned engine capabilities")
        }

        test("AUTOMATIC: TEST_PINNED with version >= 3.26 and legacy_alter_table=false folds the rename") {
            val capabilities = RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.SQLITE,
                source = RenameCapabilitySource.TEST_PINNED,
                sqliteVersion = "3.27.0",
                sqliteLegacyAlterTable = false,
            )
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameTable>()
        }

        test("BLOCKED: TEST_PINNED but legacy_alter_table=true blocks even on 3.30") {
            val capabilities = RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.SQLITE,
                source = RenameCapabilitySource.TEST_PINNED,
                sqliteVersion = "3.30.0",
                sqliteLegacyAlterTable = true,
            )
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.shouldBeEmpty()
            projection.diagnostics.single().code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
        }

        test("BLOCKED: TEST_PINNED with version < 3.26 blocks") {
            val capabilities = RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.SQLITE,
                source = RenameCapabilitySource.TEST_PINNED,
                sqliteVersion = "3.25.999",
                sqliteLegacyAlterTable = false,
            )
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.shouldBeEmpty()
        }

        test("BLOCKED: unparsable version string is treated as unknown capability") {
            val capabilities = RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.SQLITE,
                source = RenameCapabilitySource.TEST_PINNED,
                sqliteVersion = "garbage",
                sqliteLegacyAlterTable = false,
            )
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith("users" to simpleTable())
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.shouldBeEmpty()
        }
    }

    // ── Default-FunctionCall blocker — uniform across dialects ───────

    context("DefaultValue.FunctionCall conservative-block applies to every policy") {
        test("PostgreSQL blocks on FunctionCall default in the renamed table") {
            val capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.POSTGRESQL)
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith(
                "users" to simpleTable(extraDefault = DefaultValue.FunctionCall("now()")),
            )
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.shouldBeEmpty()
            val diag = projection.diagnostics.single()
            diag.code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
            diag.message.shouldContain("now()")
        }

        test("MySQL blocks on FunctionCall default in the renamed table") {
            val capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.MYSQL)
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith(
                "users" to simpleTable(extraDefault = DefaultValue.FunctionCall("uuid()")),
            )
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.diagnostics.single().code shouldBe RENAME_DEPENDENCY_UNPROJECTABLE
        }

        test("SQLite reports both the FunctionCall blocker AND the unknown-capability blocker") {
            val capabilities = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.SQLITE)
            val projector = RenameDependencyProjector(capabilities)
            val schema = schemaWith(
                "users" to simpleTable(extraDefault = DefaultValue.FunctionCall("hex(randomblob(8))")),
            )
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

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

    // ── Version parser regression pins (T3 specifically tests
    //    SQLite-policy edge cases that depend on the parser; the
    //    parser itself has its own RenameProjectionVersionParserTest) ──

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
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = projector.projectTables(listOf(item), SchemaDiff(), schema, schema)

            projection.operations.shouldBeEmpty()
            projection.diagnostics.shouldHaveSize(1)
        }
    }
})
