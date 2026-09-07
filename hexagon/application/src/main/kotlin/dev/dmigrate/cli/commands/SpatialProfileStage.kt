package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * Haelt `--spatial-profile none` auf dem Migrate-Pfad ein.
 *
 * `schema generate` blockt eine Tabelle mit Geometriespalten unter diesem
 * Profil mit `E052`. Auf dem Migrate-Pfad las kein Renderer das Profil —
 * ausser SQLite —, die Angabe blieb dort also wirkungslos: `SDO_GEOMETRY`
 * bzw. `geometry` entstanden trotzdem.
 *
 * Der Lauf wird deshalb abgelehnt, nicht die einzelne Tabelle. Ein
 * Migrationsplan ist abhaengigkeitssortiert; eine Tabelle daraus zu
 * entfernen liesse die uebrigen Anweisungen auf etwas verweisen, das nicht
 * entsteht. Wer Geometrie migrieren will, waehlt ein Profil, das sie
 * rendert; wer sie nicht will, laesst die Spalte aus dem Sollschema.
 */
internal object SpatialProfileStage {

    sealed interface Outcome {
        data object Allowed : Outcome
        data class Refused(val operations: List<String>) : Outcome
    }

    /**
     * @return [Outcome.Refused] mit den betroffenen Objekten, wenn [profile]
     *   keine Geometrie rendern kann und der Plan welche einfuehrt.
     */
    fun run(plan: DiffResult, profile: SpatialProfile): Outcome {
        if (profile != SpatialProfile.NONE) return Outcome.Allowed
        val affected = plan.operations.filter(::introducesGeometry).map { it.objectRef.displayName }
        return if (affected.isEmpty()) Outcome.Allowed else Outcome.Refused(affected.distinct().sorted())
    }

    fun buildFailureResult(operations: List<String>): MigrationDdlResult {
        val diagnostic = DiffDiagnostic(
            code = "E052",
            message = "Spatial profile 'none' cannot render geometry, but the plan introduces it for: " +
                operations.joinToString(", "),
            severity = DiffDiagnostic.Severity.BLOCKER,
        )
        return MigrationDdlResult(
            statements = emptyList(),
            operationsRendered = emptySet(),
            blockers = listOf(
                MigrationBlocker(
                    reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                    diagnostics = listOf(diagnostic),
                ),
            ),
            primaryBlockedReason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            diagnostics = listOf(diagnostic),
        )
    }

    private fun introducesGeometry(operation: DiffOperation): Boolean = when (operation) {
        is DiffOperation.CreateTable -> operation.table.columns.values.any { it.type is NeutralType.Geometry }
        is DiffOperation.AddColumn -> operation.column.type is NeutralType.Geometry
        is DiffOperation.AlterColumnType -> operation.after is NeutralType.Geometry
        else -> false
    }
}
