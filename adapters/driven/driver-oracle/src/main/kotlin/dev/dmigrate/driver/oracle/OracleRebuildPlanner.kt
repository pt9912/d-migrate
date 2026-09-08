package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.inOrdinalOrder
import dev.dmigrate.core.util.sha256Hex

/**
 * Erkennt die eine Aenderung, die Oracle nicht per `ALTER` kann, und sammelt
 * alles ein, was mit ihr zusammen laufen muss.
 *
 * **Der Ausloeser.** Eine bestehende gewoehnliche Spalte laesst sich nicht
 * nachtraeglich zur Identity-Spalte machen: `ALTER TABLE … MODIFY <col>
 * GENERATED ALWAYS AS IDENTITY` antwortet auf jeder Spalte, die nicht bereits
 * Identity traegt, mit `ORA-30673` — gemessen an leerer Tabelle, gefuellter
 * Tabelle und Spalte mit NULL-Wert. Die Gegenrichtung geht
 * (`MODIFY <col> DROP IDENTITY`), und Praezisions- oder Modus-Aenderungen an
 * einer bereits identity-tragenden Spalte laufen in-place. **Nur das
 * Hinzufuegen** braucht den Neubau.
 *
 * Das ist der Unterschied zu SQL Server, wo auch das Entfernen und der
 * Basistypwechsel einen Neubau erzwingen ([MssqlRebuildPlanner]-Gegenstueck).
 *
 * **Warum absorbiert wird.** Der Neubau legt die Tabelle in ihrem Zielzustand
 * an. Jede andere Operation auf derselben Tabelle waere danach nicht
 * ueberfluessig, sondern falsch: ein `CREATE INDEX` fuer einen Index, den der
 * Neubau schon angelegt hat, scheitert mit `ORA-00955`. Sie wandern deshalb in
 * denselben Eimer und gelten mit ihm als gerendert.
 *
 * **Eingehende Fremdschluessel** gehoeren dazu, obwohl sie auf anderen Tabellen
 * sitzen: `DROP TABLE … CASCADE CONSTRAINTS` raeumt sie mit ab, und wer sie
 * danach nicht wieder anlegt, verliert sie still.
 */
internal object OracleRebuildPlanner {

    /** Ein Neubau: die Tabelle, die ausloesende Operation und alles, was mitlaeuft. */
    data class Rebuild(val table: String, val trigger: DiffOperation, val ops: List<DiffOperation>)

    /** Die Neubauten, nach Tabellenname sortiert. Alles Uebrige rendert der Dispatcher einzeln. */
    data class Classification(val rebuilds: List<Rebuild>) {
        private val absorbed: Set<String> = rebuilds.flatMap { it.ops }.map { it.id }.toSet()

        /** Ob diese Operation ein Neubau miterledigt — der Dispatcher laesst sie dann liegen. */
        fun isAbsorbed(op: DiffOperation): Boolean = op.id in absorbed
    }

    /** Ein Fremdschluessel einer anderen Tabelle, der auf die neu gebaute zeigt. */
    data class InboundForeignKey(val childTable: String, val constraint: ConstraintDefinition)

    fun classify(
        ops: List<DiffOperation>,
        currentSchema: SchemaDefinition?,
        desiredSchema: SchemaDefinition?,
        direction: OracleRenderDirection,
    ): Classification {
        val triggers = ops
            .filter { requiresRebuild(it, currentSchema, desiredSchema, direction) }
            .mapNotNull { op -> tableOf(op)?.let { it to op } }
        if (triggers.isEmpty()) return Classification(emptyList())
        // Je Tabelle die ERSTE ausloesende Operation: sie bestimmt nur, wem die
        // Diagnosen zugerechnet werden — gebaut wird die Tabelle ganz.
        val triggerByTable = triggers.groupBy({ it.first }, { it.second }).mapValues { it.value.first() }
        val buckets = LinkedHashMap<String, MutableList<DiffOperation>>()
        for (table in triggerByTable.keys.sorted()) buckets[table] = mutableListOf()
        for (op in ops) {
            val own = tableOf(op)
            val bucket = if (own != null && own in buckets) own else referencedRebuildTable(op, buckets.keys)
            if (bucket != null) buckets.getValue(bucket) += op
        }
        return Classification(
            buckets.map { (table, bucket) -> Rebuild(table, triggerByTable.getValue(table), bucket) },
        )
    }

    /**
     * Der Ausloeser: eine Typaenderung, die Identity **hinzufuegt**.
     *
     * Gefragt wird nicht nur der Typ, sondern die Spalte — Identity kommt
     * ebenso aus `generation`. Nur den Typ zu pruefen liesse den Neubau genau
     * dort aus, wo das Schema sie so fuehrt, und das `ALTER` liefe gegen
     * `ORA-30673`.
     *
     * Die Richtung entscheidet mit, welche Seite das Ziel ist: dieselbe
     * Operation fuegt in der einen Richtung Identity hinzu und entfernt sie in
     * der anderen. Ohne diese Unterscheidung baute die Umkehrung einer
     * Identity-Einfuehrung die Tabelle neu, obwohl Oracle das Entfernen per
     * `ALTER` kann.
     */
    fun requiresRebuild(
        op: DiffOperation,
        currentSchema: SchemaDefinition?,
        desiredSchema: SchemaDefinition?,
        direction: OracleRenderDirection,
    ): Boolean {
        if (op !is DiffOperation.AlterColumnType) return false
        val table = op.objectRef.path.firstOrNull() ?: return false
        val column = op.objectRef.path.getOrNull(1) ?: return false
        val up = direction == OracleRenderDirection.UP
        val targetSchema = if (up) desiredSchema else currentSchema
        val sourceSchema = if (up) currentSchema else desiredSchema
        val targetType = if (up) op.after else op.before
        val sourceType = if (up) op.before else op.after
        val target = targetSchema?.tables?.get(table)?.columns?.get(column)
        val source = sourceSchema?.tables?.get(table)?.columns?.get(column)
        val becomesIdentity = OracleIdentity.isIdentity(targetType, target)
        val wasIdentity = OracleIdentity.isIdentity(sourceType, source)
        return becomesIdentity && !wasIdentity
    }

    /**
     * Der Name der Zwischentabelle. Er entsteht aus den Operations-IDs des
     * Eimers, ist also ueber zwei Laeufe mit derselben Eingabe stabil — ein
     * abgebrochener Lauf hinterlaesst keinen Namen, den der naechste meidet.
     *
     * Oracle begrenzt Bezeichner auf 128 Zeichen (ab 12.2); der Tabellenteil
     * wird dafuer notfalls gekuerzt, nicht der unterscheidende Hash.
     */
    fun tempTableName(table: String, bucket: List<DiffOperation>): String {
        val suffix = "__dmg_rebuild_" + sha256Hex(bucket.map { it.id }.sorted().joinToString("")).take(8)
        return table.take(MAX_IDENTIFIER_LENGTH - suffix.length) + suffix
    }

    /** Welche Zielspalte woher gefuellt wird. */
    sealed interface ColumnSource {
        /** Die Spalte gab es vorher — unter diesem Namen. */
        data class From(val column: String) : ColumnSource

        /** Neue Spalte: der Neubau fuellt sie aus ihrem Default (oder NULL). */
        data object Fill : ColumnSource
    }

    fun columnSources(
        source: TableDefinition,
        target: TableDefinition,
        bucket: List<DiffOperation>,
        direction: OracleRenderDirection,
    ): List<Pair<String, ColumnSource>> {
        val renames = renameMap(bucket, direction)
        return target.columns.inOrdinalOrder().map { (name, _) ->
            val previousName = renames[name] ?: name
            name to if (source.columns.containsKey(previousName)) {
                ColumnSource.From(previousName)
            } else {
                ColumnSource.Fill
            }
        }
    }

    /** Zielspaltenname → Quellspaltenname, aus den absorbierten Umbenennungen. */
    private fun renameMap(
        bucket: List<DiffOperation>,
        direction: OracleRenderDirection,
    ): Map<String, String> = bucket.filterIsInstance<DiffOperation.RenameColumn>().associate { op ->
        if (direction == OracleRenderDirection.UP) op.toName to op.fromName else op.fromName to op.toName
    }

    /**
     * Die Fremdschluessel anderer Tabellen, die im **Zielschema** auf [table]
     * zeigen — genau die, die der Neubau nach dem Umbenennen wieder anlegt.
     *
     * `DROP TABLE … CASCADE CONSTRAINTS` raeumt sie beim Neubau ab; ohne das
     * Wiederanlegen verschwaenden sie still. Die Zielseite ist die richtige
     * Grundlage: was dort nicht mehr steht, soll auch nicht wiederkommen.
     *
     * Eine Kindtabelle, die es auf der Quellseite noch nicht gibt, bleibt
     * aussen vor: ihr Fremdschluessel wurde nie mit abgeraeumt, und ihr
     * `CREATE TABLE` bringt ihn selbst mit — hier noch einmal angelegt liefe
     * er gegen eine Tabelle, die zu diesem Zeitpunkt fehlt.
     */
    fun inboundForeignKeys(
        targetSchema: SchemaDefinition?,
        sourceSchema: SchemaDefinition?,
        table: String,
    ): List<InboundForeignKey> {
        if (targetSchema == null) return emptyList()
        return targetSchema.tables.entries.sortedBy { it.key }.flatMap { (childName, child) ->
            if (childName == table || sourceSchema?.tables?.containsKey(childName) != true) {
                return@flatMap emptyList()
            }
            child.constraints
                .filter { it.type == ConstraintType.FOREIGN_KEY && it.references?.table == table }
                .map { InboundForeignKey(childName, it) }
        }
    }

    /** Ob diese Spalte in der gebauten Tabelle eine Identity-Spalte wird. */
    fun isIdentity(col: ColumnDefinition): Boolean = OracleIdentity.isIdentity(col.type, col)

    private fun tableOf(op: DiffOperation): String? = op.objectRef.path.firstOrNull()

    /**
     * Eine Constraint-Operation auf einer ANDEREN Tabelle, die auf eine neu
     * gebaute zeigt, gehoert trotzdem in deren Eimer: der Neubau raeumt jeden
     * eingehenden Fremdschluessel ab und stellt den Zielzustand wieder her.
     * Sie danach noch einmal zu rendern erzeugte `ORA-00955`.
     */
    private fun referencedRebuildTable(op: DiffOperation, rebuilt: Set<String>): String? {
        val constraint = when (op) {
            is DiffOperation.AddConstraint -> op.constraint
            is DiffOperation.DropConstraint -> op.constraint
            else -> null
        } ?: return null
        if (constraint.type != ConstraintType.FOREIGN_KEY) return null
        return constraint.references?.table?.takeIf { it in rebuilt }
    }

    private const val MAX_IDENTIFIER_LENGTH = 128
}
