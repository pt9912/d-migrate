package dev.dmigrate.core.diff

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
    ): Boolean {
        when (authorship?.authorChanged(objectType, path, field, keyPosition, authoredNow)) {
            false -> return true
            true -> return false
            null -> Unit
        }
        // Keine Herkunft: dann fragt der Sandkasten, was der Server aus dem
        // Autorentext machen wuerde. Zwei Serverformen sind vergleichbar.
        val deparsed = serverForm?.deparsed(objectType, path, field, keyPosition) ?: return false
        return deparsed == catalogNow.orEmpty()
    }

    private companion object {
        const val VIEW_QUERY = "query"
        const val EXPRESSION = "expression"
        const val WHERE = "where"
        const val KEY_EXPRESSION = "key-expression"
    }
}
