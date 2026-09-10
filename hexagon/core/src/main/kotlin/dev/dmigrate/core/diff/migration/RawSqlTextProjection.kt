package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.EnumCheckProjection
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition

/**
 * Blendet die Felder aus, die rohen SQL-Text tragen.
 *
 * Das neutrale Modell fuehrt an fuenf Stellen Text statt Bausteinen:
 * `ViewDefinition.query`, `ConstraintDefinition.expression`,
 * `IndexDefinition.where`, `IndexColumn.expression` und
 * `ColumnGeneration.Computed.expression`. Server geben ihn nicht
 * wortgleich zurueck — PostgreSQL druckt ihn aus seinem Parsebaum, ein
 * `--`-Kommentar ist danach spurlos weg. Ein Autorentext und eine Katalogform
 * desselben Ausdrucks stimmen deshalb nie ueberein.
 *
 * **Wofuer das gedacht ist und wofuer nicht.** Diese Projektion gehoert
 * ausschliesslich in den Post-Compare, wo beide Seiten anschliessend gegen
 * gleichartige Formen geprueft werden ([ADR 0053](../../../../../../../../docs/adr/0053-vergleich-rohen-sql-texts.md)).
 * Sie darf **nicht** in die Identitaet eingehen — weder in den Comparator noch
 * in `CanonicalPayload`: der Text IST die Aussage einer Sicht, eines CHECKs und
 * eines Ausdrucks-Index, und ihn dort auszublenden hiesse, eine echte Aenderung
 * nicht mehr zu sehen.
 *
 * **Eine Ausnahme, und zwar eine notwendige.** Ein CHECK, der den Wertevorrat
 * einer Spalte aufzaehlt, ist kein unvergleichbarer Text: [EnumCheckProjection]
 * liest seine Form. Ihn auszublenden nimmt dem Fingerabdruck genau die Angabe,
 * aus der er beide Darstellungen eines Enums zur Deckung bringt — und ein
 * Lauf, der eine Enum-Spalte anlegt, meldete danach Drift auf einer Migration,
 * die getan hat, was verlangt war. Er wird deshalb nicht ausgeblendet, sondern
 * auf seine kanonische Form gebracht.
 */
object RawSqlTextProjection {

    /**
     * Der Platzhalter. Ein fester Wert und nicht `null`: so bleibt das Feld in
     * der Projektion sichtbar (gesetzt vs. nicht gesetzt bleibt unterscheidbar),
     * nur sein Inhalt zaehlt nicht mehr.
     */
    const val PLACEHOLDER: String = "<raw-sql>"

    fun blank(schema: SchemaDefinition): SchemaDefinition = schema.copy(
        tables = schema.tables.mapValues { (_, table) -> blank(table) },
        views = schema.views.mapValues { (_, view) -> blank(view) },
    )

    private fun blank(table: TableDefinition): TableDefinition = table.copy(
        columns = table.columns.mapValues { (_, column) -> blank(column) },
        indices = table.indices.map(::blank),
        constraints = table.constraints.map { blank(it, table.columns.keys) },
    )

    /**
     * Der Berechnungsausdruck einer Spalte. Die Speicherform daneben bleibt
     * stehen: sie ist keine Textfrage, und der Vergleich soll sie sehen.
     */
    private fun blank(column: ColumnDefinition): ColumnDefinition {
        val computed = column.generation as? ColumnGeneration.Computed ?: return column
        return column.copy(generation = computed.copy(expression = PLACEHOLDER))
    }

    private fun blank(index: IndexDefinition): IndexDefinition = index.copy(
        where = index.where?.let { PLACEHOLDER },
        columns = index.columns.map(::blank),
    )

    private fun blank(column: IndexColumn): IndexColumn = if (column.expression == null) {
        column
    } else {
        // Auch `name` traegt bei einem Ausdrucks-Index denselben Text noch
        // einmal, als Etikett. Bliebe er stehen, waere der Text weiterhin im
        // Vergleich.
        column.copy(name = PLACEHOLDER, expression = PLACEHOLDER)
    }

    private fun blank(constraint: ConstraintDefinition, columns: Set<String>): ConstraintDefinition =
        constraint.copy(expression = constraint.expression?.let { enumCheckText(it, columns) ?: PLACEHOLDER })

    /**
     * Die kanonische Form, wenn der Ausdruck den Wertevorrat einer der
     * [columns] aufzaehlt — sonst `null`.
     *
     * Die erste passende Spalte gewinnt, wie beim Falten im Fingerabdruck: ein
     * Ausdruck kann sich nur auf eine Spalte beziehen, und die Reihenfolge der
     * Suche darf das Ergebnis nicht bestimmen.
     */
    private fun enumCheckText(expression: String, columns: Set<String>): String? {
        for (column in columns) {
            val values = EnumCheckProjection.valuesOf(expression, column) ?: continue
            return EnumCheckProjection.canonicalText(column, values)
        }
        return null
    }

    private fun blank(view: ViewDefinition): ViewDefinition =
        view.copy(query = view.query?.let { PLACEHOLDER })
}
