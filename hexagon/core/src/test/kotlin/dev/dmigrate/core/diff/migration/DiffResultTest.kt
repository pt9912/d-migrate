package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DiffResultTest : FunSpec({

    fun emptyDiff() = SchemaDiff(
        schemaMetadata = null,
        customTypesAdded = emptyList(), customTypesRemoved = emptyList(), customTypesChanged = emptyList(),
        tablesAdded = emptyList(), tablesRemoved = emptyList(), tablesChanged = emptyList(),
        viewsAdded = emptyList(), viewsRemoved = emptyList(), viewsChanged = emptyList(),
        sequencesAdded = emptyList(), sequencesRemoved = emptyList(), sequencesChanged = emptyList(),
        functionsAdded = emptyList(), functionsRemoved = emptyList(), functionsChanged = emptyList(),
        proceduresAdded = emptyList(), proceduresRemoved = emptyList(), proceduresChanged = emptyList(),
        triggersAdded = emptyList(), triggersRemoved = emptyList(), triggersChanged = emptyList(),
    )

    val current = DiffEndpoint("acme", schemaVersion = "1")
    val desired = DiffEndpoint("acme", schemaVersion = "2")

    test("empty DiffResult: no operations, no diagnostics, no blockers, fully reversible") {
        val result = DiffResult(current, desired, emptyDiff(), emptyList())
        result.operations.shouldBe(emptyList())
        result.diagnostics.shouldBe(emptyList())
        result.hasBlockers shouldBe false
        result.isFullyReversible shouldBe true
    }

    test("hasBlockers true iff diagnostics include a BLOCKER severity") {
        val info = DiffDiagnostic("X", "info", DiffDiagnostic.Severity.INFO)
        val warn = DiffDiagnostic("Y", "warn", DiffDiagnostic.Severity.WARNING)
        val blocker = DiffDiagnostic("Z", "blocked", DiffDiagnostic.Severity.BLOCKER)

        DiffResult(current, desired, emptyDiff(), emptyList(), listOf(info))
            .hasBlockers shouldBe false
        DiffResult(current, desired, emptyDiff(), emptyList(), listOf(warn))
            .hasBlockers shouldBe false
        DiffResult(current, desired, emptyDiff(), emptyList(), listOf(info, blocker))
            .hasBlockers shouldBe true
    }

    test("isFullyReversible reflects per-operation Reversibility") {
        val tableRef = DiffObjectRef(DiffObjectType.TABLE, listOf("t"))
        val columnRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("t", "c"))

        val reversibleOnly = DiffResult(
            current, desired, emptyDiff(),
            operations = listOf(
                DiffOperation.AddColumn("a", columnRef, ColumnDefinition(NeutralType.Text())),
                DiffOperation.AlterColumnDefault("b", columnRef, before = null, after = null),
            ),
        )
        reversibleOnly.isFullyReversible shouldBe true

        val withDropTable = DiffResult(
            current, desired, emptyDiff(),
            operations = listOf(
                DiffOperation.AddColumn("a", columnRef, ColumnDefinition(NeutralType.Text())),
                DiffOperation.DropTable("c", tableRef, TableDefinition()),
            ),
        )
        withDropTable.isFullyReversible shouldBe false
    }
})
