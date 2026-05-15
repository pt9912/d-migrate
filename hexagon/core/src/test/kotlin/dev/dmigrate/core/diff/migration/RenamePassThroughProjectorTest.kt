package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * F.4 dependency-projection T2: direct tests for
 * [RenamePassThroughProjector]. The end-to-end planner tests
 * (`RenameOverlayMapperTest`) already cover the fold path
 * transitively, but those tests cannot exercise `postRenameDeltaOperations`
 * (always empty in T2) nor the `error(...)` invariants in the
 * candidate-bound diagnostic builders. T3/T4 will rely on this
 * surface — pin it now.
 */
class RenamePassThroughProjectorTest : FunSpec({

    fun tableCandidate(
        from: String = "users_old",
        to: String = "users",
        structurallyEqual: Boolean = true,
        staleReferenceObject: String? = null,
        differences: List<String> = emptyList(),
    ) = RenameTableCandidate(
        id = "rename-table-$from-$to",
        fromName = from,
        toName = to,
        overlaySource = "ovl/rename.json",
        overlayEntryId = "entry-1",
        overlayHash = "sha256:abc",
        structurallyEqual = structurallyEqual,
        structuralDifferences = differences,
        staleReferenceObject = staleReferenceObject,
    )

    fun columnCandidate(
        table: String = "users",
        from: String = "email_addr",
        to: String = "email",
        structurallyEqual: Boolean = true,
        referencingObject: String? = null,
        differences: List<String> = emptyList(),
    ) = RenameColumnCandidate(
        id = "rename-column-$table-$from-$to",
        tableName = table,
        fromColumn = from,
        toColumn = to,
        overlaySource = "ovl/rename.json",
        overlayEntryId = "entry-c",
        overlayHash = "sha256:def",
        structurallyEqual = structurallyEqual,
        structuralDifferences = differences,
        referencingObject = referencingObject,
    )

    // ── projectTables ────────────────────────────────────────────────

    context("projectTables") {
        test("empty items returns the cached empty projection") {
            val projection = RenamePassThroughProjector.projectTables(emptyList())
            projection.operations.shouldBeEmpty()
            projection.diagnostics.shouldBeEmpty()
            projection.absorbedFromNames.shouldBeEmpty()
            projection.absorbedToNames.shouldBeEmpty()
        }

        test("structurally-equal candidate emits RenameTable + absorbs both names") {
            val item = RenameTablePlanningItem(candidate = tableCandidate())
            val projection = RenamePassThroughProjector.projectTables(listOf(item))

            projection.operations.shouldHaveSize(1)
            val op = projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameTable>()
            op.id shouldBe "rename-table-users_old-users"
            op.fromName shouldBe "users_old"
            op.toName shouldBe "users"
            op.overlaySource shouldBe "ovl/rename.json"
            op.overlayHash shouldBe "sha256:abc"

            projection.diagnostics.shouldBeEmpty()
            projection.absorbedFromNames shouldContainExactly setOf("users_old")
            projection.absorbedToNames shouldContainExactly setOf("users")
        }

        test("postRenameDeltaOperations append after the rename in the produced order") {
            // T4 will populate this list; pinning the order now prevents
            // a regression where the projector silently drops or reorders
            // the synthetic deltas relative to the rename op.
            val syntheticDelta = DiffOperation.AddColumn(
                id = "add-col-users-email_verified",
                objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("users", "email_verified")),
                column = ColumnDefinition(type = NeutralType.BooleanType),
            )
            val item = RenameTablePlanningItem(
                candidate = tableCandidate(),
                postRenameDeltaOperations = listOf(syntheticDelta),
            )
            val projection = RenamePassThroughProjector.projectTables(listOf(item))

            projection.operations.shouldHaveSize(2)
            projection.operations[0].shouldBeInstanceOf<DiffOperation.RenameTable>()
            projection.operations[1] shouldBe syntheticDelta
        }

        test("structural mismatch produces RENAME_OVERLAY_STRUCTURAL_MISMATCH warning, no rename op") {
            val item = RenameTablePlanningItem(
                candidate = tableCandidate(
                    structurallyEqual = false,
                    differences = listOf("removed columns [legacy_id]"),
                ),
            )
            val projection = RenamePassThroughProjector.projectTables(listOf(item))

            projection.operations.shouldBeEmpty()
            projection.absorbedFromNames.shouldBeEmpty()
            projection.absorbedToNames.shouldBeEmpty()

            projection.diagnostics.shouldHaveSize(1)
            val diag = projection.diagnostics.single()
            diag.code shouldBe RenameOverlayMapper.STRUCTURAL_MISMATCH
            diag.severity shouldBe DiffDiagnostic.Severity.WARNING
            diag.message.shouldContain("removed columns [legacy_id]")
            diag.message.shouldContain("'users_old' -> 'users'")
        }

        test("stale reference produces RENAME_OVERLAY_DEPENDENCY_PROJECTION_REQUIRED warning") {
            val item = RenameTablePlanningItem(
                candidate = tableCandidate(staleReferenceObject = "orders.fk_users"),
            )
            val projection = RenamePassThroughProjector.projectTables(listOf(item))

            projection.operations.shouldBeEmpty()
            projection.diagnostics.shouldHaveSize(1)
            val diag = projection.diagnostics.single()
            diag.code shouldBe RenameOverlayMapper.DEPENDENCY_PROJECTION_REQUIRED
            diag.severity shouldBe DiffDiagnostic.Severity.WARNING
            diag.message.shouldContain("orders.fk_users")
        }

        test("structural mismatch shadows stale-reference probe (mismatch wins)") {
            // Defensive: prepare() zeroes staleReferenceObject when
            // structurallyEqual is false. Verify the projector also
            // prefers the structural-mismatch path when (against the
            // current builder contract) a candidate carries both signals.
            val item = RenameTablePlanningItem(
                candidate = tableCandidate(
                    structurallyEqual = false,
                    differences = listOf("primary key [id] -> [pk_id]"),
                    staleReferenceObject = "orders.fk_users",
                ),
            )
            val projection = RenamePassThroughProjector.projectTables(listOf(item))
            projection.diagnostics.single().code shouldBe RenameOverlayMapper.STRUCTURAL_MISMATCH
        }

        test("multiple items preserve order and accumulate absorbed sets") {
            val ok = RenameTablePlanningItem(candidate = tableCandidate())
            val mismatch = RenameTablePlanningItem(
                candidate = tableCandidate(
                    from = "products_old",
                    to = "products",
                    structurallyEqual = false,
                    differences = listOf("removed columns [sku]"),
                ),
            )
            val ok2 = RenameTablePlanningItem(
                candidate = tableCandidate(from = "orders_old", to = "orders"),
            )
            val projection = RenamePassThroughProjector.projectTables(listOf(ok, mismatch, ok2))

            projection.operations.map { it.id } shouldContainExactly listOf(
                "rename-table-users_old-users",
                "rename-table-orders_old-orders",
            )
            projection.absorbedFromNames shouldContainExactly setOf("users_old", "orders_old")
            projection.absorbedToNames shouldContainExactly setOf("users", "orders")
            projection.diagnostics.shouldHaveSize(1)
        }
    }

    // ── projectColumns ───────────────────────────────────────────────

    context("projectColumns") {
        test("empty items returns the cached empty projection") {
            val projection = RenamePassThroughProjector.projectColumns(emptyList())
            projection.operations.shouldBeEmpty()
            projection.diagnostics.shouldBeEmpty()
            projection.absorbedFromColumns.shouldBeEmpty()
            projection.absorbedToColumns.shouldBeEmpty()
        }

        test("structurally-equal candidate emits RenameColumn + absorbs both names") {
            val item = RenameColumnPlanningItem(candidate = columnCandidate())
            val projection = RenamePassThroughProjector.projectColumns(listOf(item))

            val op = projection.operations.single().shouldBeInstanceOf<DiffOperation.RenameColumn>()
            op.id shouldBe "rename-column-users-email_addr-email"
            op.fromName shouldBe "email_addr"
            op.toName shouldBe "email"
            op.overlaySource shouldBe "ovl/rename.json"

            projection.absorbedFromColumns shouldContainExactly setOf("email_addr")
            projection.absorbedToColumns shouldContainExactly setOf("email")
            projection.diagnostics.shouldBeEmpty()
        }

        test("structural mismatch produces STRUCTURAL_MISMATCH warning, no rename op") {
            val item = RenameColumnPlanningItem(
                candidate = columnCandidate(
                    structurallyEqual = false,
                    differences = listOf("type Text(200) -> Text(255)"),
                ),
            )
            val projection = RenamePassThroughProjector.projectColumns(listOf(item))

            projection.operations.shouldBeEmpty()
            projection.diagnostics.shouldHaveSize(1)
            val diag = projection.diagnostics.single()
            diag.code shouldBe RenameOverlayMapper.STRUCTURAL_MISMATCH
            diag.message.shouldContain("type Text(200) -> Text(255)")
            diag.message.shouldContain("'users.email_addr' -> 'users.email'")
        }

        test("referencingObject produces DEPENDENCY_PROJECTION_REQUIRED warning") {
            val item = RenameColumnPlanningItem(
                candidate = columnCandidate(referencingObject = "index idx_users_email"),
            )
            val projection = RenamePassThroughProjector.projectColumns(listOf(item))

            projection.operations.shouldBeEmpty()
            projection.diagnostics.shouldHaveSize(1)
            val diag = projection.diagnostics.single()
            diag.code shouldBe RenameOverlayMapper.DEPENDENCY_PROJECTION_REQUIRED
            diag.message.shouldContain("index idx_users_email")
        }

        test("postRenameDeltaOperations append after the rename in the produced order") {
            val syntheticAlter = DiffOperation.AlterColumnType(
                id = "alter-col-type-users-email",
                objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("users", "email")),
                before = NeutralType.Text(maxLength = 200),
                after = NeutralType.Text(maxLength = 255),
            )
            val item = RenameColumnPlanningItem(
                candidate = columnCandidate(),
                postRenameDeltaOperations = listOf(syntheticAlter),
            )
            val projection = RenamePassThroughProjector.projectColumns(listOf(item))

            projection.operations.shouldHaveSize(2)
            projection.operations[0].shouldBeInstanceOf<DiffOperation.RenameColumn>()
            projection.operations[1] shouldBe syntheticAlter
        }
    }

    // ── candidate→operation ID parity ────────────────────────────────

    context("candidate id is preserved on the emitted operation") {
        test("table") {
            val candidate = tableCandidate()
            val op = RenameOverlayMapper.buildRenameTableOperation(candidate)
            op.id shouldBe candidate.id
        }

        test("column") {
            val candidate = columnCandidate()
            val op = RenameOverlayMapper.buildRenameColumnOperation(candidate)
            op.id shouldBe candidate.id
        }
    }

    // ── invariant: diagnostic builders refuse to fire without their flag ──

    context("diagnostic builders enforce their invariant") {
        test("staleReferenceTableDiagnostic requires a non-null staleReferenceObject") {
            shouldThrow<IllegalStateException> {
                RenameOverlayMapper.staleReferenceTableDiagnostic(tableCandidate(staleReferenceObject = null))
            }
        }

        test("dependencyProjectionColumnDiagnostic requires a non-null referencingObject") {
            shouldThrow<IllegalStateException> {
                RenameOverlayMapper.dependencyProjectionColumnDiagnostic(columnCandidate(referencingObject = null))
            }
        }
    }
})
