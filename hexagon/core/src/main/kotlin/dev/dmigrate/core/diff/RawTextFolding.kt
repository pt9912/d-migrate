package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.IndexDefinition

/**
 * Faltet ein rohes SQL-Textfeld des Soll-Schemas auf die Form des Ziels, wenn
 * die Herkunft sagt, dass der **Autor** es nicht angefasst hat.
 *
 * Dann ist die abweichende Schreibweise die des Servers und keine Aenderung.
 * Ohne Herkunft faltet nichts — es bleibt beim Textvergleich, und der plant
 * konservativ.
 *
 * Gefaltet wird **nur, woran verglichen wird**. Die gemeldete Aenderung traegt
 * weiterhin die unveraenderten Definitionen; sonst flosse eine
 * Vergleichs-Projektion in die erzeugte DDL — dieselbe Grenze, die
 * [TargetProjection] zieht.
 */
internal class RawTextFolding(
    private val authorship: RawTextAuthorship?,
    private val serverForm: RawTextServerForm? = null,
) {

    /** Der CHECK-Ausdruck eines benannten Constraints. */
    fun constraint(
        tableName: String,
        constraintName: String,
        current: ConstraintDefinition,
        desired: ConstraintDefinition,
    ): ConstraintDefinition {
        val path = listOf(tableName, constraintName)
        if (!unchanged("constraint", path, EXPRESSION, null, desired.expression, current.expression)) {
            return desired
        }
        return desired.copy(expression = current.expression)
    }

    /** Praedikat und Ausdrucks-Schluessel eines Index. */
    fun index(tableName: String, current: IndexDefinition, desired: IndexDefinition): IndexDefinition {
        if (authorship == null && serverForm == null) return desired
        val name = desired.name ?: current.name ?: return desired
        val path = listOf(tableName, name)
        val withWhere = if (unchanged("index", path, WHERE, null, desired.where, current.where)) {
            desired.copy(where = current.where)
        } else {
            desired
        }
        return withWhere.copy(
            columns = withWhere.columns.mapIndexed { position, key ->
                val currentKey = current.columns.getOrNull(position)
                when {
                    currentKey == null -> key
                    key.expression == null && currentKey.expression == null -> key
                    !unchanged(
                        "index", path, KEY_EXPRESSION, position + 1, key.expression, currentKey.expression,
                    ) -> key
                    // Auch das Etikett traegt den Ausdruck noch einmal; bliebe
                    // es stehen, waere der Text weiterhin im Vergleich.
                    else -> key.copy(name = currentKey.name, expression = currentKey.expression)
                }
            },
        )
    }

    /**
     * Die Berechnung einer Spalte.
     *
     * Gefaltet wird nur der Ausdruck. Die Speicherform daneben ist keine
     * Textfrage: sie steht als eigenes Feld und soll sichtbar bleiben.
     */
    fun columnGeneration(
        tableName: String,
        columnName: String,
        current: ColumnDefinition,
        desired: ColumnDefinition,
    ): ColumnDefinition {
        val desiredComputed = desired.generation as? ColumnGeneration.Computed ?: return desired
        val currentComputed = current.generation as? ColumnGeneration.Computed ?: return desired
        val decision = decide(
            "column", listOf(tableName, columnName), GENERATION_EXPRESSION, null,
            desiredComputed.expression, currentComputed.expression,
        )
        // **Hier heisst "nicht entscheidbar" nicht "konservativ planen".** Bei
        // den vier anderen Textfeldern kostet ein Fehlalarm ein
        // `CREATE OR REPLACE`; bei einer berechneten Spalte kostet er eine
        // Neuschreibung der Tabelle unter exklusiver Sperre, und je nach Server
        // scheitert der Weg an einer abhaengigen Sicht oder verliert einen
        // Index (gemessen, siehe
        // `docs/planning/open/generated-column-expression-dropped.md`).
        //
        // Wo keine Quelle etwas sagen kann, wird deshalb nichts geplant. Dass
        // die Frage offenblieb, meldet die Anwendungsschicht — verschwiegen
        // wird sie nicht.
        if (decision == false) return desired
        return desired.copy(generation = desiredComputed.copy(expression = currentComputed.expression))
    }

    /** Der Rumpf einer Sicht — als Text, weil der Vergleich dort feldweise laeuft. */
    fun viewQuery(viewName: String, current: String?, desired: String?): String? =
        if (unchanged("view", listOf(viewName), VIEW_QUERY, null, desired, current)) current else desired

    /**
     * Zwei Quellen, dieselbe Entscheidung — die Herkunft zuerst, weil sie den
     * Server nicht braucht.
     */
    private fun unchanged(
        objectType: String,
        path: List<String>,
        field: String,
        keyPosition: Int?,
        authoredNow: String?,
        catalogNow: String?,
    ): Boolean = decide(objectType, path, field, keyPosition, authoredNow, catalogNow) == true

    /**
     * `true` = unveraendert, `false` = geaendert, `null` = **nicht
     * entscheidbar**, weil weder Herkunft noch Sandkasten etwas sagen koennen.
     *
     * Die drei Faelle auseinanderzuhalten ist noetig, weil sie nicht ueberall
     * dieselbe Folge haben: fuer die meisten Felder ist "nicht entscheidbar"
     * wie "geaendert" zu behandeln (konservativ), fuer den Berechnungsausdruck
     * einer Spalte nicht.
     */
    private fun decide(
        objectType: String,
        path: List<String>,
        field: String,
        keyPosition: Int?,
        authoredNow: String?,
        catalogNow: String?,
    ): Boolean? {
        authorship?.authorChanged(objectType, path, field, keyPosition, authoredNow)?.let { changed ->
            return !changed
        }
        // Keine Herkunft: dann fragt der Sandkasten, was der Server aus dem
        // Autorentext machen wuerde. Zwei Serverformen sind vergleichbar.
        val deparsed = serverForm?.deparsed(objectType, path, field, keyPosition) ?: return null
        return deparsed == catalogNow.orEmpty()
    }

    private companion object {
        const val VIEW_QUERY = "query"
        const val EXPRESSION = "expression"
        const val WHERE = "where"
        const val KEY_EXPRESSION = "key-expression"
        const val GENERATION_EXPRESSION = "generation-expression"
    }
}
