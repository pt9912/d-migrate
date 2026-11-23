package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition

/**
 * Welche Wertevorrat-CHECKs der zielbewusste Vergleich in die Spalte faltet,
 * und welcher Wertevorrat danach je Spalte verglichen wird.
 *
 * Die Faltung ([EnumCheckProjection.foldable]) bringt eine Textspalte mit ihrem
 * `IN`-CHECK und eine `enum`-Spalte auf dieselbe Form. Sie ist fuer genau diesen
 * Abgleich da: das Soll fuehrt den Wertevorrat am Spaltentyp, die Ist-Seite als
 * CHECK. Eine Aenderung wird dann als Spaltenaenderung gemeldet, und deren
 * Neubau schreibt den CHECK mit.
 *
 * Fuehrt das Soll die Spalte dagegen **nicht** als `enum`, traegt eine
 * Spaltenaenderung keine Werteliste — ihr Typ ist eine Textspalte. Ein dort
 * gefalteter CHECK, dessen Werte sich **aendern**, verschwaende aus dem
 * Constraint-Vergleich, ohne in einer Operation anzukommen: der Plan loeste den
 * alten und legte keinen neuen an (Issue #76). Fuer eine solche Spalte bleiben
 * die CHECKs deshalb gewoehnliche Constraints, auf beiden Seiten.
 *
 * Bei **gleichem** Wertevorrat bleibt es bei der Faltung, auch ohne `enum` im
 * Soll: sonst verglichen sich die beiden Schreibweisen als Text (`IN (…)` gegen
 * PostgreSQLs `= ANY (ARRAY[…])`), und der zweite Lauf plante den CHECK neu.
 *
 * Die Renderer fragen dieselbe Regel ueber [ownsValueChecks] ab: nur ein
 * gefalteter CHECK gehoert der Spaltenaenderung, jeder andere einer
 * Constraint-Operation, die ihn selbst loest und anlegt.
 */
object EnumCheckFolding {

    internal data class Result(
        val vocabularyLeft: Map<String, List<String>>,
        val vocabularyRight: Map<String, List<String>>,
        val foldedLeft: Set<ConstraintDefinition>,
        val foldedRight: Set<ConstraintDefinition>,
    )

    /**
     * Ob die Wertevorrat-CHECKs von [column] zur Spaltenaenderung gehoeren —
     * also von ihr geloest und angelegt werden — statt zu einer eigenen
     * Constraint-Operation.
     *
     * Das ist der Fall, wenn das Soll ([desired]) die Spalte als `enum` fuehrt,
     * und dort, wo es sie gar nicht fuehrt (eine geloeschte Spalte nimmt ihren
     * CHECK mit). Sonst gehoert er nie der Spaltenaenderung: entweder ist er eine
     * eigene Constraint-Operation, oder sein Wertevorrat ist unveraendert, und
     * die Spaltenaenderung (etwa eine neue Laenge) laesst ihn stehen.
     */
    fun ownsValueChecks(desired: TableDefinition?, column: String): Boolean {
        val type = desired?.columns?.get(column)?.type ?: return true
        return type is NeutralType.Enum
    }

    /**
     * [left]/[right] sind die Tabellen, wie sie gemeldet werden; [resolvedLeft]/
     * [resolvedRight] dieselben mit aufgeloesten `ref_type`-Spalten
     * ([RefTypeVocabulary]) — aus ihnen kommt der Wertevorrat.
     */
    internal fun of(
        left: TableDefinition,
        right: TableDefinition,
        resolvedLeft: TableDefinition = left,
        resolvedRight: TableDefinition = right,
    ): Result {
        val vocabularyLeft = EnumCheckProjection.vocabulary(resolvedLeft)
        val vocabularyRight = EnumCheckProjection.vocabulary(resolvedRight)
        val unfold = right.columns.keys
            .filter { !ownsValueChecks(right, it) && vocabularyLeft[it] != vocabularyRight[it] }
            .toSet()

        return Result(
            vocabularyLeft = withoutFold(vocabularyLeft, EnumCheckProjection.declaredVocabulary(resolvedLeft), unfold),
            vocabularyRight = withoutFold(vocabularyRight, EnumCheckProjection.declaredVocabulary(resolvedRight), unfold),
            foldedLeft = folded(EnumCheckProjection.foldable(left), unfold),
            foldedRight = folded(EnumCheckProjection.foldable(right), unfold),
        )
    }

    private fun folded(
        foldable: Map<ConstraintDefinition, Pair<String, List<String>>>,
        unfold: Set<String>,
    ): Set<ConstraintDefinition> = foldable.filterValues { (column, _) -> column !in unfold }.keys

    /** Fuer eine nicht gefaltete Spalte zaehlt nur, was ihr Typ selbst fuehrt. */
    private fun withoutFold(
        vocabulary: Map<String, List<String>>,
        declared: Map<String, List<String>>,
        unfold: Set<String>,
    ): Map<String, List<String>> {
        val result = vocabulary.toMutableMap()
        for (column in unfold) {
            val fromType = declared[column]
            if (fromType == null) result.remove(column) else result[column] = fromType
        }
        return result
    }
}
