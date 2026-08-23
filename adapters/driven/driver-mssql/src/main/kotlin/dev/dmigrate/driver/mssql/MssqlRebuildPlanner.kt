package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.inOrdinalOrder
import dev.dmigrate.core.util.sha256Hex

/**
 * Erkennt die eine Operation, die SQL Server nicht per `ALTER` kann, und
 * sammelt alles ein, was mit ihr zusammen laufen muss.
 *
 * **Der Ausloeser.** IDENTITY ist keine Spalteneigenschaft, die sich setzen
 * oder loeschen laesst: `ALTER TABLE … ALTER COLUMN` kann sie weder hinzufuegen
 * (Msg 156) noch entfernen. Wer sie aendern will, baut die Tabelle neu — anlegen,
 * kopieren, alte loeschen, umbenennen. Das ist bei MSSQL die **einzige**
 * Aenderung mit diesem Zwang; SQLite braucht ihn fuer fast jede.
 *
 * **Warum absorbiert wird.** Der Neubau legt die Tabelle in ihrem Zielzustand
 * an — mit allen Spalten, Schluesseln, Constraints und Indizes, die das Schema
 * der Renderrichtung fuer sie vorsieht. Jede andere Operation auf derselben
 * Tabelle waere danach nicht etwa ueberfluessig, sondern falsch: ein
 * `CREATE INDEX` fuer einen Index, den der Neubau bereits angelegt hat,
 * scheitert mit Msg 1913 (T-SQL kennt kein `IF NOT EXISTS` fuer Indizes). Sie
 * wandern deshalb in denselben Eimer und gelten mit ihm als gerendert.
 *
 * Die Klassifikation ist deterministisch: Eimer-Reihenfolge nach Tabellenname,
 * Operationen darin in der Topo-Reihenfolge des Planners.
 */
internal object MssqlRebuildPlanner {

    /** Ein Neubau: die Tabelle, die ausloesende Operation und alles, was mitlaeuft. */
    data class Rebuild(val table: String, val trigger: DiffOperation, val ops: List<DiffOperation>)

    data class Classification(
        /** Die Neubauten, nach Tabellenname sortiert. */
        val rebuilds: List<Rebuild>,
        /** Alles andere, unveraendert in Planner-Reihenfolge. */
        val simpleOps: List<DiffOperation>,
    ) {
        val hasRebuilds: Boolean get() = rebuilds.isNotEmpty()
    }

    fun classify(
        ops: List<DiffOperation>,
        currentSchema: SchemaDefinition?,
        desiredSchema: SchemaDefinition?,
    ): Classification {
        val triggers = ops
            .filter { requiresRebuild(it, currentSchema, desiredSchema) }
            .mapNotNull { op -> tableOf(op)?.let { it to op } }
        if (triggers.isEmpty()) return Classification(emptyList(), ops)
        // Je Tabelle die ERSTE Typaenderung als Ausloeser: sie bestimmt nur, wem
        // die Diagnosen zugerechnet werden — gebaut wird die Tabelle ganz.
        val triggerByTable = triggers.groupBy({ it.first }, { it.second }).mapValues { it.value.first() }
        val buckets = LinkedHashMap<String, MutableList<DiffOperation>>()
        for (table in triggerByTable.keys.sorted()) buckets[table] = mutableListOf()
        val simple = mutableListOf<DiffOperation>()
        for (op in ops) {
            val own = tableOf(op)
            val bucket = if (own != null && own in buckets && isAbsorbed(op)) {
                own
            } else {
                referencedRebuildTable(op, buckets.keys)
            }
            if (bucket != null) buckets.getValue(bucket) += op else simple += op
        }
        return Classification(
            rebuilds = buckets.map { (table, bucket) -> Rebuild(table, triggerByTable.getValue(table), bucket) },
            simpleOps = simple,
        )
    }

    /**
     * Der Ausloeser: eine Typaenderung, an der IDENTITY beteiligt ist — sie
     * hinzuzufuegen, sie zu entfernen **oder** den Basistyp einer Spalte zu
     * aendern, die IDENTITY bleibt.
     *
     * Der dritte Fall gehoert dazu, obwohl er nach einer gewoehnlichen
     * Typaenderung aussieht: `ALTER TABLE … ALTER COLUMN` lehnt jede
     * Neudeklaration einer IDENTITY-Spalte mit Msg 156 ab, `int identity` →
     * `bigint identity` ist also genauso ein Neubau wie die beiden anderen.
     *
     * Gefragt wird nicht nur der Typ, sondern die Spalte: IDENTITY kommt
     * ebenso aus `generation` (`MssqlColumnConstraintHelper.isIdentity`).
     * Nur den Typ zu pruefen liesse den Neubau genau dort aus, wo das Schema
     * die Identity ueber `generation` fuehrt — und ein `ALTER COLUMN` liefe
     * gegen Msg 156.
     */
    fun requiresRebuild(
        op: DiffOperation,
        currentSchema: SchemaDefinition?,
        desiredSchema: SchemaDefinition?,
    ): Boolean {
        if (op !is DiffOperation.AlterColumnType) return false
        val table = op.objectRef.path.firstOrNull() ?: return false
        val column = op.objectRef.path.getOrNull(1) ?: return false
        val before = currentSchema?.tables?.get(table)?.columns?.get(column)
        val after = desiredSchema?.tables?.get(table)?.columns?.get(column)
        return MssqlColumnConstraintHelper.isIdentity(op.before, before) ||
            MssqlColumnConstraintHelper.isIdentity(op.after, after)
    }

    /** Ob diese Spalte in der gebauten Tabelle eine IDENTITY-Spalte wird. */
    fun isIdentity(col: ColumnDefinition): Boolean = MssqlColumnConstraintHelper.isIdentity(col.type, col)

    /**
     * Der Name der Zwischentabelle. Er entsteht aus den Operations-IDs des
     * Eimers, ist also ueber zwei Laeufe mit derselben Eingabe stabil — ein
     * abgebrochener Lauf hinterlaesst keinen Namen, den der naechste meidet,
     * und ein wiederholter Lauf erzeugt denselben.
     *
     * SQL Server begrenzt Bezeichner auf 128 Zeichen; der Tabellenteil wird
     * dafuer notfalls gekuerzt, nicht der unterscheidende Hash.
     */
    fun tempTableName(table: String, bucket: List<DiffOperation>): String {
        val suffix = "__dmg_rebuild_" + sha256Hex(bucket.map { it.id }.sorted().joinToString("")).take(8)
        val room = MAX_IDENTIFIER_LENGTH - suffix.length
        return table.take(room) + suffix
    }

    /**
     * Welche Zielspalte woher gefuellt wird. Grundlage ist der Spaltenname; ein
     * absorbiertes [DiffOperation.RenameColumn] verschiebt ihn, damit der
     * Neubau die Umbenennung gleich miterledigt statt danach ein `sp_rename`
     * auf eine Spalte zu schicken, die es unter dem alten Namen nicht mehr gibt.
     */
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
        direction: MssqlRenderDirection,
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
        direction: MssqlRenderDirection,
    ): Map<String, String> = bucket.filterIsInstance<DiffOperation.RenameColumn>().associate { op ->
        if (direction == MssqlRenderDirection.UP) op.toName to op.fromName else op.fromName to op.toName
    }

    /**
     * Fremdschluessel **anderer** Tabellen auf diese: sie muessen vor dem
     * `DROP TABLE` weichen und danach wieder entstehen. Ohne den ersten Schritt
     * lehnt SQL Server das `DROP TABLE` ab (Msg 3726).
     */
    /**
     * Die eingehenden Fremdschluessel, die bei Beginn des Neubaus
     * **tatsaechlich in der Datenbank stehen** — und die er deshalb abraeumen
     * muss, sonst scheitert sein `DROP TABLE` mit Msg 3726.
     *
     * Grundlage ist nicht die Phasenordnung, sondern was der Renderer bis
     * hierher wirklich geschrieben hat ([renderedBefore]): der Ausgangszustand
     * plus alles, was eine schon gerenderte Operation angelegt hat — eine neue
     * Kindtabelle bringt ihre Fremdschluessel inline mit, eine neue Spalte
     * ihren `references`.
     *
     * Abwaerts entfaellt der zweite Teil: dort **entfernen** die vorangehenden
     * Operationen, sie legen nichts an. Die einzige Ausnahme waere die Umkehr
     * eines `DropConstraint` — und genau die absorbiert der Eimer.
     */
    fun inboundForeignKeysPresent(
        sourceSchema: SchemaDefinition?,
        targetSchema: SchemaDefinition?,
        table: String,
        renderedBefore: List<DiffOperation>,
        direction: MssqlRenderDirection,
    ): List<MssqlDiffColumnDependencies.InboundForeignKey> {
        val fromSource = MssqlDiffColumnDependencies.inboundForeignKeys(sourceSchema, table)
        if (direction == MssqlRenderDirection.DOWN) return fromSource
        return (fromSource + materialisedBy(renderedBefore, targetSchema, table))
            .distinctBy(MssqlDiffColumnDependencies::keyOf)
    }

    /** Die eingehenden Fremdschluessel, die eine bereits gerenderte Operation angelegt hat. */
    private fun materialisedBy(
        renderedBefore: List<DiffOperation>,
        targetSchema: SchemaDefinition?,
        table: String,
    ): List<MssqlDiffColumnDependencies.InboundForeignKey> {
        val createdTables = renderedBefore.filterIsInstance<DiffOperation.CreateTable>()
            .map { it.objectRef.rootName }
            .toSet()
        val addedColumns = renderedBefore.filterIsInstance<DiffOperation.AddColumn>()
            .mapNotNull { op ->
                val path = op.objectRef.path
                if (path.size >= 2) path[0] to path[1] else null
            }
            .toSet()
        return MssqlDiffColumnDependencies.inboundForeignKeys(targetSchema, table).filter { fk ->
            fk.childTable in createdTables ||
                fk.constraint.columns.orEmpty().any { (fk.childTable to it) in addedColumns }
        }
    }

    /**
     * Was der Neubau nach dem Umbenennen wieder anlegt: was er abgeraeumt hat
     * und das Ziel weiterhin vorsieht — plus die Fremdschluessel, deren eigene
     * Operation er absorbiert und deren Arbeit er damit uebernommen hat.
     *
     * Absorbiert sind BEIDE Richtungen: ein `AddConstraint` aufwaerts, ein
     * `DropConstraint`, dessen Umkehr abwaerts ein `ADD` waere. Nur die eine
     * Seite zu betrachten verlor den Fremdschluessel beim Rollback still.
     * Die Zugehoerigkeit zum Zielzustand entscheidet ohnehin, ob er entsteht.
     *
     * Zusammen ist das genau die Menge, fuer die sonst niemand mehr ein
     * Statement schreibt. Alles darueber hinaus entstuende doppelt (Msg 2714)
     * oder zu frueh, auf einer Spalte, die es noch nicht gibt (Msg 1911).
     */
    fun inboundForeignKeysToRestore(
        sourceSchema: SchemaDefinition?,
        targetSchema: SchemaDefinition?,
        table: String,
        renderedBefore: List<DiffOperation>,
        direction: MssqlRenderDirection,
        bucket: List<DiffOperation>,
    ): List<MssqlDiffColumnDependencies.InboundForeignKey> {
        val present = inboundForeignKeysPresent(sourceSchema, targetSchema, table, renderedBefore, direction)
            .map(MssqlDiffColumnDependencies::keyOf)
            .toSet()
        val absorbed = bucket.mapNotNull { op ->
            val name = when (op) {
                is DiffOperation.AddConstraint -> op.constraint.name
                is DiffOperation.DropConstraint -> op.constraint.name
                else -> null
            } ?: return@mapNotNull null
            op.objectRef.path.firstOrNull()?.let { it to name }
        }.toSet()
        return MssqlDiffColumnDependencies.inboundForeignKeys(targetSchema, table)
            .filter { MssqlDiffColumnDependencies.keyOf(it) in present || MssqlDiffColumnDependencies.keyOf(it) in absorbed }
    }

    /**
     * Eine Constraint-Operation auf einer ANDEREN Tabelle, die auf eine neu
     * gebaute zeigt, gehoert trotzdem in deren Eimer: der Neubau raeumt jeden
     * eingehenden Fremdschluessel ab und stellt den Zielzustand wieder her.
     * Liefe die Operation zusaetzlich, gaebe es den Fremdschluessel zweimal
     * (Msg 2714) — oder sie fiele ins Leere, weil der Neubau ihn schon
     * geloescht hat.
     */
    private fun referencedRebuildTable(op: DiffOperation, rebuiltTables: Set<String>): String? {
        val constraint = when (op) {
            is DiffOperation.AddConstraint -> op.constraint
            is DiffOperation.DropConstraint -> op.constraint
            else -> null
        } ?: return null
        if (constraint.type != ConstraintType.FOREIGN_KEY) return null
        return constraint.references?.table?.takeIf { it in rebuiltTables }
    }

    /**
     * Was der Neubau uebernimmt. Alles, was die Gestalt der Tabelle betrifft —
     * inklusive der Index- und Constraint-Operationen, anders als beim
     * SQLite-Pendant: dort ist `CREATE INDEX IF NOT EXISTS` moeglich, in T-SQL
     * nicht, ein zweites Anlegen scheitert also statt zu verpuffen.
     */
    private fun isAbsorbed(op: DiffOperation): Boolean = when (op) {
        is DiffOperation.AddColumn,
        is DiffOperation.DropColumn,
        is DiffOperation.RenameColumn,
        is DiffOperation.AlterColumnType,
        is DiffOperation.AlterColumnNullability,
        is DiffOperation.AlterColumnDefault,
        is DiffOperation.AddPrimaryKey,
        is DiffOperation.DropPrimaryKey,
        is DiffOperation.AddConstraint,
        is DiffOperation.DropConstraint,
        is DiffOperation.AddIndex,
        is DiffOperation.DropIndex,
        -> true

        else -> false
    }

    /**
     * `CreateTable`, `DropTable` und `RenameTable` fehlen bewusst: eine Tabelle,
     * die im selben Plan entsteht oder verschwindet, wird nicht umgebaut, und
     * eine umbenannte behaelt ihre eigene Operation — der Neubau laeuft dann
     * an der Stelle, an der der Planner die Typaenderung einsortiert hat, also
     * nach der Umbenennung.
     */
    private fun tableOf(op: DiffOperation): String? = when (op) {
        is DiffOperation.AddColumn,
        is DiffOperation.DropColumn,
        is DiffOperation.RenameColumn,
        is DiffOperation.AlterColumnType,
        is DiffOperation.AlterColumnNullability,
        is DiffOperation.AlterColumnDefault,
        is DiffOperation.AddConstraint,
        is DiffOperation.DropConstraint,
        is DiffOperation.AddIndex,
        is DiffOperation.DropIndex,
        -> op.objectRef.path.firstOrNull()

        is DiffOperation.AddPrimaryKey,
        is DiffOperation.DropPrimaryKey,
        -> op.objectRef.rootName

        else -> null
    }

    private const val MAX_IDENTIFIER_LENGTH = 128
}
