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
 * Ohne Herkunft und ohne Sandkasten faltet nichts — es bleibt beim
 * Textvergleich, und der plant konservativ. **Ausnahme:** wenn der Aufrufer
 * [canonicalizeRawExpressions] setzt, entscheidet zusaetzlich die
 * Dialekt-Schreibweise.
 *
 * Gefaltet wird **nur, woran verglichen wird**. Die gemeldete Aenderung traegt
 * weiterhin die unveraenderten Definitionen; sonst flosse eine
 * Vergleichs-Projektion in die erzeugte DDL — dieselbe Grenze, die
 * [TargetProjection] zieht.
 */
internal class RawTextFolding(
    private val authorship: RawTextAuthorship?,
    private val serverForm: RawTextServerForm? = null,
    /**
     * Ob zusaetzlich die **Dialekt-Schreibweise** roher Ausdruecke gleichgesetzt
     * wird — eine **dritte** Entscheidungsquelle neben Herkunft und Sandkasten,
     * die ohne beide auskommt.
     *
     * Default `false`: der konservative Weg. `schema compare` setzt sie, weil
     * dort ein Fehlalarm nur einen Fund kostet; `schema migrate` **nicht**, weil
     * dort eine uebersehene Aenderung eine falsch stehende Datenbank kostet
     * (ADR 0056).
     */
    private val canonicalizeRawExpressions: Boolean = false,
) {

    /** Der CHECK-Ausdruck eines benannten Constraints. */
    fun constraint(
        tableName: String,
        constraintName: String,
        current: ConstraintDefinition,
        desired: ConstraintDefinition,
    ): ConstraintDefinition {
        val path = listOf(tableName, constraintName)
        // Nur wenn angefordert — und nur "unveraendert" kommt dabei heraus:
        // was sie nicht gleichsetzt, entscheiden die beiden anderen Quellen.
        if (canonicalizeRawExpressions &&
            ConstraintDiffContract.canonicallyEqual(desired.expression, current.expression)
        ) {
            return desired.copy(expression = current.expression)
        }
        if (!unchanged("constraint", path, EXPRESSION, null, desired.expression, current.expression)) {
            return desired
        }
        return desired.copy(expression = current.expression)
    }

    /**
     * Praedikat und Ausdrucks-Schluessel eines Index.
     *
     * Die Dialekt-Schreibweise greift — wenn angefordert — **nur** am
     * Praedikat, und sie steht **vor** dem Guard der beiden anderen Quellen:
     * `schema compare` setzt weder Herkunft noch Sandkasten, und ein Guard, der
     * vorher zurueckkehrte, liesse das Praedikat dort ungefaltet. Den Guard
     * stattdessen zu verschieben, kippte den Migrate-Pfad, der die
     * Schreibweise nicht faltet (ADR 0056). Die Schluessel-Ausdruecke bleiben
     * auch in `schema compare` wortgleich.
     */
    fun index(tableName: String, current: IndexDefinition, desired: IndexDefinition): IndexDefinition {
        val spelled = whereSpelling(current, desired)
        if (authorship == null && serverForm == null) return spelled
        val name = desired.name ?: current.name ?: return spelled
        val path = listOf(tableName, name)
        val withWhere = when {
            // Schon ueber die Schreibweise als unveraendert erkannt.
            spelled.where != desired.where -> spelled
            unchanged("index", path, WHERE, null, desired.where, current.where) -> spelled.copy(where = current.where)
            else -> spelled
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
     * Das Soll mit dem Praedikat des Ist, wenn beide sich nur in der
     * Dialekt-Schreibweise unterscheiden — dieselbe Regel wie beim
     * CHECK-Ausdruck. Sonst unveraendert.
     */
    private fun whereSpelling(current: IndexDefinition, desired: IndexDefinition): IndexDefinition {
        if (!canonicalizeRawExpressions || desired.where == current.where) return desired
        if (!ConstraintDiffContract.canonicallyEqual(desired.where, current.where)) return desired
        return desired.copy(where = current.where)
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

    /**
     * Der Rumpf einer Sicht — als Text, weil der Vergleich dort feldweise laeuft.
     *
     * Zusaetzlich zu Herkunft und Sandkasten greift hier die
     * **Dialekt-Schreibweise**, wenn der Aufrufer sie angefordert hat: die drei
     * Dialekte quoten Bezeichner verschieden (`"id"`, `` `id` ``, `[id]`) und
     * setzen unterschiedlich viel Whitespace. Dasselbe Reverse ergab damit
     * einen `VIEW_CHANGED`-Fund, obwohl die Abfrage dieselbe war.
     *
     * **Bewusst eng** — was dort gleichgesetzt wird und was nicht, steht bei
     * [QuerySpelling].
     */
    fun viewQuery(viewName: String, current: String?, desired: String?): String? {
        if (canonicalizeRawExpressions && QuerySpelling.equal(desired, current)) return current
        return if (unchanged("view", listOf(viewName), VIEW_QUERY, null, desired, current)) current else desired
    }

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
