package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.RawTextAuthorship
import dev.dmigrate.core.diff.RawTextServerForm
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.diff.migration.overlay.RawTextProvenanceFields

/**
 * Wo der Vergleich ueber den Berechnungsausdruck einer Spalte **nichts sagen
 * kann** — und das deshalb sagt.
 *
 * Der Ausdruck ist roher SQL-Text: die Autorenform und die Form, die der Server
 * fuehrt, stimmen nie ueberein. Entscheidbar wird die Frage erst, wenn zwei
 * gleichartige Formen gegeneinanderstehen — ueber die Herkunft (zwei
 * Autorentexte) oder ueber den Sandkasten (zwei Serverformen).
 *
 * Liegt keines von beidem vor, plant der Lauf nichts: eine Aenderung an einer
 * berechneten Spalte kostet die Neuschreibung der Tabelle unter exklusiver
 * Sperre, und je nach Server scheitert sie an einer abhaengigen Sicht oder
 * verliert einen Index. Ein Fehlalarm ist hier also teurer als bei den uebrigen
 * rohen Textfeldern, wo konservativ geplant wird.
 *
 * Nichts zu planen heisst aber nicht, nichts zu sagen: waere die Frage
 * unbeantwortet **und** unerwaehnt, aenderte jemand den Ausdruck, bekaeme
 * Exit 0 und die Datenbank rechnete weiter nach der alten Formel.
 */
internal object ComputedExpressionDecidability {

    /** Die Frage blieb offen — es wurde nichts geplant. */
    const val UNDECIDED: String = "W137"

    /**
     * Die Frage war beantwortet, und die Antwort lautet "geaendert" — nur
     * ausfuehren kann der Lauf sie nicht.
     */
    const val UNSUPPORTED_CHANGE: String = "E137"

    fun diagnostics(
        current: SchemaDefinition,
        desired: SchemaDefinition,
        authorship: RawTextAuthorship?,
        serverForm: RawTextServerForm?,
    ): List<DiffDiagnostic> = buildList {
        for ((tableName, desiredTable) in desired.tables) {
            val currentTable = current.tables[tableName] ?: continue
            for ((columnName, desiredColumn) in desiredTable.columns) {
                val desiredExpression = expressionOf(desiredColumn.generation) ?: continue
                val currentExpression = expressionOf(currentTable.columns[columnName]?.generation) ?: continue
                // Wortgleich braucht keine Quelle.
                if (desiredExpression == currentExpression) continue
                val path = "$tableName.$columnName"
                when (decide(tableName, columnName, desiredExpression, currentExpression, authorship, serverForm)) {
                    null -> add(undecidedNote(path))
                    true -> add(unsupportedChange(path))
                    false -> Unit
                }
            }
        }
    }

    private fun undecidedNote(path: String) = DiffDiagnostic(
        code = UNDECIDED,
        message = "The computed expression of column `$path` was not compared: its authored form and the " +
            "form the server keeps never match literally, and neither a `raw-text-provenance` overlay nor " +
            "the raw-SQL sandbox was available to decide. A change to it would NOT have been migrated.",
        severity = DiffDiagnostic.Severity.WARNING,
    )

    private fun unsupportedChange(path: String) = DiffDiagnostic(
        code = UNSUPPORTED_CHANGE,
        message = "The computed expression of column `$path` has changed, but `schema migrate` cannot apply " +
            "that change: altering it rewrites the table under an exclusive lock, and on some servers the " +
            "only available route drops the column — losing its indexes, and failing outright when a view " +
            "depends on it. Change the expression manually on the target, or drop and recreate the column.",
        severity = DiffDiagnostic.Severity.BLOCKER,
    )

    /**
     * `true` = geaendert, `false` = unveraendert, `null` = nicht entscheidbar.
     * Dieselbe Reihenfolge wie im Vergleich: die Herkunft zuerst, weil sie den
     * Server nicht braucht.
     */
    private fun decide(
        tableName: String,
        columnName: String,
        authoredNow: String,
        catalogNow: String,
        authorship: RawTextAuthorship?,
        serverForm: RawTextServerForm?,
    ): Boolean? {
        val path = listOf(tableName, columnName)
        val field = RawTextProvenanceFields.COLUMN_GENERATION_EXPRESSION
        authorship?.authorChanged("column", path, field, null, authoredNow)?.let { return it }
        val deparsed = serverForm?.deparsed("column", path, field, null) ?: return null
        return deparsed != catalogNow
    }

    private fun expressionOf(generation: ColumnGeneration?): String? =
        (generation as? ColumnGeneration.Computed)?.expression
}
