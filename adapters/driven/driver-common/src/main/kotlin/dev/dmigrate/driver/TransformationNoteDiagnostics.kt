package dev.dmigrate.driver

import dev.dmigrate.core.diff.migration.DiffDiagnostic

/**
 * Dieselbe Meldung, einmal fuers Erzeugen und einmal fuers Migrieren.
 *
 * Der Generate-Pfad haengt seine Hinweise als [TransformationNote] an das
 * erzeugte Statement; der Diff-Pfad fuehrt sie als [DiffDiagnostic] am Report.
 * Was ein Dialekt beim Erzeugen meldet, muss er beim Migrieren genauso melden --
 * sonst warnt `schema generate` vor einem Verlust, den `schema migrate`
 * stillschweigend hinnimmt.
 *
 * Die Abbildung steht deshalb an einer Stelle statt in jedem Treiber neu.
 * `ACTION_REQUIRED` wird bewusst zu `WARNING`: die Diagnose-Ebene des Diffs
 * kennt keine dritte Stufe, und ein Blocker waere sie nicht -- das Statement
 * entsteht ja.
 */
fun TransformationNote.asDiffDiagnostic(operationId: String): DiffDiagnostic = DiffDiagnostic(
    code = code,
    message = hint?.let { "$message $it" } ?: message,
    severity = when (type) {
        NoteType.ACTION_REQUIRED, NoteType.WARNING -> DiffDiagnostic.Severity.WARNING
        NoteType.INFO -> DiffDiagnostic.Severity.INFO
    },
    operationId = operationId,
)
