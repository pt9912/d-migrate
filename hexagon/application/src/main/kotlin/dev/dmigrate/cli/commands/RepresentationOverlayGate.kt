package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidationContext
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidator
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect

/**
 * Prueft Darstellungs-Overlays gegen **ein** Schema — Bindung, Abdruck,
 * Dialekt.
 *
 * Zwei Befehle stehen vor derselben Lage: `schema reverse` haelt das gerade
 * gelesene Schema in der Hand, `schema generate` das gerade geladene. Beide
 * kennen kein Schema*paar*, koennen ein Uebergangs-Overlay also weder pruefen
 * noch sinnvoll deuten — und beide muessen pruefen, **bevor** das Overlay
 * wirkt: ein veraltetes setzte sonst still eine falsche Darstellung, und das
 * Ergebnis saehe aus wie ein gutes.
 */
internal object RepresentationOverlayGate {

    /**
     * Ein Wert, den kein Abdruck annehmen kann. Wo es kein Schemapaar gibt,
     * ist ein Uebergangs-Overlay ein Irrtum, und der soll benannt auffallen
     * statt zufaellig durchzugehen.
     */
    private const val UNAVAILABLE_FINGERPRINT = "<no transition binding here>"

    /**
     * `true`, wenn der Lauf abbrechen soll. Jede Diagnose geht ueber
     * [printError] hinaus — auch die nicht blockierenden, sonst erfuehre der
     * Anwender von einer Beobachtung nichts, die seine naechste Datei betrifft.
     */
    fun rejects(
        documents: List<MigrationOverlayDocument>,
        schema: SchemaDefinition,
        dialect: DatabaseDialect,
        printError: (String, String) -> Unit,
    ): Boolean {
        if (documents.isEmpty()) return false
        val expected = PartitionOverlayHint.representationFingerprint(schema, dialect)
        var blocked = false
        for (document in documents) {
            val result = MigrationOverlayValidator.validate(
                overlay = document.overlay,
                context = MigrationOverlayValidationContext(
                    expectedSourceFingerprint = UNAVAILABLE_FINGERPRINT,
                    expectedTargetFingerprint = UNAVAILABLE_FINGERPRINT,
                    expectedDialect = dialect.name.lowercase(),
                    expectedRepresentationFingerprint = expected,
                ),
                source = document.source,
            )
            for (diagnostic in result.diagnostics) {
                printError("[${diagnostic.code}] ${diagnostic.message}", document.source)
            }
            if (result.hasBlockers) blocked = true
        }
        return blocked
    }
}
