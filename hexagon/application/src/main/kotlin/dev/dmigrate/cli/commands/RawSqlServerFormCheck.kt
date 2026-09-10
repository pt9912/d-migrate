package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.SchemaDefinition

/**
 * Vergleicht die vier rohen SQL-Textfelder **Server-Form gegen Server-Form**.
 *
 * Der Post-Compare fragt, ob die Migration getan hat, was der Plan sagte. Fuer
 * ein Textfeld kann er das nicht am Dateitext pruefen: der Server gibt ihn nie
 * wortgleich zurueck, ein `--`-Kommentar ist nach dem Zurueckschreiben spurlos
 * weg. Beide Formen gegeneinander zu stellen meldete deshalb bei **jedem** Lauf
 * Drift ([ADR 0053](../../../../../../../../docs/adr/0053-vergleich-rohen-sql-texts.md)).
 *
 * Gegeneinander gestellt werden hier stattdessen zwei Formen **desselben
 * Servers**: wie er das Feld vor dem Anwenden fuehrte und wie danach. Fuer ein
 * Objekt, das der Plan nicht angefasst hat, muessen sie gleich sein — weicht
 * eines ab, hat jemand von Hand geschrieben, und das ist echte Drift.
 *
 * Objekte, die der Plan anfasst, bleiben aussen vor: dort ist eine Abweichung
 * die Absicht des Laufs. Dass der Server den neuen Text angenommen hat, ist
 * seine eigene Bestaetigung — verglichen wird nicht gegen den Dateitext,
 * sondern gar nicht.
 */
internal object RawSqlServerFormCheck {

    /**
     * Die Abweichungen, oder eine leere Liste. Jede Zeile nennt Objekt, Feld
     * und beide Formen — **ohne** sie zu kuerzen: wer eine Handaenderung sucht,
     * braucht den Unterschied, nicht seine Zusammenfassung.
     */
    fun handChanges(
        before: SchemaDefinition,
        after: SchemaDefinition,
        untouched: (String) -> Boolean,
    ): List<String> {
        val findings = mutableListOf<String>()
        for ((name, beforeTable) in before.tables) {
            if (!untouched(name)) continue
            val afterTable = after.tables[name] ?: continue
            compareIndices(name, beforeTable.indices, afterTable.indices, findings)
            compareConstraints(name, beforeTable, afterTable, findings)
            compareColumnGenerations(name, beforeTable, afterTable, findings)
        }
        for ((name, beforeView) in before.views) {
            if (!untouched(name)) continue
            val afterView = after.views[name] ?: continue
            report(findings, "view '$name'", "query", beforeView.query, afterView.query)
        }
        return findings
    }

    /**
     * Der Berechnungsausdruck einer Spalte — dasselbe Wesen wie ein
     * CHECK-Ausdruck, und derselbe Vergleich: zwei Formen desselben Servers,
     * vor und nach dem Lauf.
     */
    private fun compareColumnGenerations(
        table: String,
        before: TableDefinition,
        after: TableDefinition,
        findings: MutableList<String>,
    ) {
        for ((columnName, beforeColumn) in before.columns) {
            val afterColumn = after.columns[columnName] ?: continue
            val beforeExpression = (beforeColumn.generation as? ColumnGeneration.Computed)?.expression
            val afterExpression = (afterColumn.generation as? ColumnGeneration.Computed)?.expression
            report(
                findings,
                "column '$columnName' on '$table'",
                "generation expression",
                beforeExpression,
                afterExpression,
            )
        }
    }

    private fun compareIndices(
        table: String,
        before: List<IndexDefinition>,
        after: List<IndexDefinition>,
        findings: MutableList<String>,
    ) {
        val afterByName = after.filter { it.name != null }.associateBy { it.name }
        for (index in before) {
            val counterpart = afterByName[index.name ?: continue] ?: continue
            report(findings, "index '${index.name}' on '$table'", "where", index.where, counterpart.where)
            // Ausdrucks-Schluessel werden ueber ihre Stellung verglichen: die
            // Reihenfolge der Schluessel ist bei einem Index bedeutungstragend.
            for ((position, column) in index.columns.withIndex()) {
                val other = counterpart.columns.getOrNull(position) ?: continue
                report(
                    findings,
                    "index '${index.name}' on '$table', key ${position + 1}",
                    "expression",
                    column.expression,
                    other.expression,
                )
            }
        }
    }

    private fun compareConstraints(
        table: String,
        before: dev.dmigrate.core.model.TableDefinition,
        after: dev.dmigrate.core.model.TableDefinition,
        findings: MutableList<String>,
    ) {
        val afterByName = after.constraints.associateBy { it.name }
        for (constraint in before.constraints) {
            val counterpart = afterByName[constraint.name] ?: continue
            report(
                findings,
                "constraint '${constraint.name}' on '$table'",
                "expression",
                constraint.expression,
                counterpart.expression,
            )
        }
    }

    private fun report(
        findings: MutableList<String>,
        subject: String,
        field: String,
        before: String?,
        after: String?,
    ) {
        if (before == after) return
        findings += "$subject: $field was ${quoted(before)} before the run and is ${quoted(after)} now"
    }

    private fun quoted(value: String?): String = value?.let { "'$it'" } ?: "unset"
}
