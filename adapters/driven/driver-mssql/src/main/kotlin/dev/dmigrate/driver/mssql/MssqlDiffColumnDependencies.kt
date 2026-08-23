package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition

/**
 * Die Objekte, die einer Spaltenaenderung im Weg stehen (Msg 5074) — abraeumen
 * davor, wiederherstellen danach.
 *
 * Drei Regeln, die sich aus den Fehlerquellen ergeben:
 *
 * 1. **Abgeraeumt wird nach dem IST-Zustand, nicht nach der Renderrichtung.**
 *    Ein `DROP COLUMN` faehrt aufwaerts gegen das Soll-Schema — und dort steht
 *    die Spalte gerade nicht mehr, samt ihrer Indizes. Wer die Abhaengigkeiten
 *    dort sucht, findet nie welche und schickt ein `DROP COLUMN`, das die
 *    Datenbank ablehnt. Massgeblich ist das Schema, das die Spalte noch
 *    **beschreibt**.
 * 2. **Wiederhergestellt wird nur, was in BEIDEN Schemata steht.** Die
 *    Spaltenphase rendert vor `CONSTRAINTS`/`INDEXES`. Ein Index, den derselbe
 *    Plan hinzufuegt, wuerde sonst zweimal entstehen (Msg 1913); einen, den er
 *    entfernt, wuerde dieser Tanz wieder anlegen, bevor die eigentliche
 *    `DropIndex`-Operation ihn loescht.
 * 3. **Namen kommen aus dem Modell, wo der Reverse sie mitbringt.** Fuer
 *    Indizes, benannte Constraints und Fremdschluessel tut er das; ein
 *    einspaltiges UNIQUE hebt er dagegen auf `column.unique` und wirft den
 *    Namen weg. Nur dort wird im Katalog nachgeschlagen — pauschal alles zu
 *    loeschen, was an der Spalte haengt, traefe auch Objekte, die das Modell
 *    gar nicht kennt (z. B. INCLUDE-Spalten, `R341`).
 */
internal object MssqlDiffColumnDependencies {

    /**
     * @param bearing das Schema, das die Spalte noch beschreibt (Regel 1)
     * @param surviving das Schema nach der Aenderung — bestimmt, was
     *   wiederhergestellt wird (Regel 2); `null` heisst „nichts wiederherstellen"
     * @param alsoPresent Fremdschluessel, die kein Schema an dieser Stelle
     *   fuehrt, die aber eine schon gerenderte Operation angelegt hat
     *   ([materialisedBy]) — sie stehen im Weg wie jeder andere.
     */
    fun of(
        table: String,
        column: String,
        bearing: SchemaDefinition?,
        surviving: SchemaDefinition?,
        alsoPresent: List<InboundForeignKey> = emptyList(),
    ): ColumnDependencies {
        // Ohne die Tabelle im massgeblichen Schema gibt es keine Spalte zu
        // aendern — dann steht auch nichts im Weg.
        val bearingTable = bearing?.tables?.get(table) ?: return ColumnDependencies.EMPTY
        val survivingTable = surviving?.tables?.get(table)
        return ColumnDependencies(
            table = table,
            column = column,
            indices = bearingTable.indices.filter { idx -> idx.columns.any { it.name == column } },
            constraints = bearingTable.constraints.filter { column in (it.columns ?: emptyList()) },
            hasColumnUnique = bearingTable.columns[column]?.unique == true,
            hasColumnReference = bearingTable.columns[column]?.references != null,
            inPrimaryKey = column in bearingTable.primaryKey,
            inboundForeignKeys = (inboundForeignKeys(bearing, table, column) + alsoPresent)
                .distinctBy(::keyOf),
            surviving = survivingTable,
            survivingSchema = surviving,
        )
    }

    /**
     * Fremdschluessel **anderer** Tabellen auf diese. SQL Server lehnt
     * `ALTER COLUMN` auch dann mit Msg 5074 ab, wenn die Abhaengigkeit von
     * aussen kommt — die eigene Tabelle allein zu betrachten reicht nicht;
     * beim `DROP TABLE` des Neubaus waere es Msg 3726.
     *
     * Erfasst **beide** Formen, in denen das Modell einen Fremdschluessel
     * fuehrt: als Eintrag in `constraints` und als `references` an einer
     * Spalte. Die zweite ist im Modell kein Constraint, in der Datenbank aber
     * sehr wohl einer — der Generate-Pfad legt sie als `fk_<kind>_<spalte>` an.
     * Der Reverse liefert zwar immer die erste Form, das Soll-Schema kommt aber
     * aus YAML und darf die zweite benutzen.
     *
     * Ein Kind darf denselben Fremdschluessel in beiden Formen fuehren — das
     * beschreibt EIN Objekt, nicht zwei; die Liste ist deshalb entdoppelt.
     * Ohne das legte der Wiederherstellungs-Pfad ihn zweimal an (Msg 2714).
     *
     * @param column nur Fremdschluessel auf DIESE Spalte; `null` = alle auf die Tabelle
     */
    fun inboundForeignKeys(
        schema: SchemaDefinition?,
        table: String,
        column: String? = null,
    ): List<InboundForeignKey> = schema?.tables.orEmpty().flatMap { (childName, child) ->
        if (childName == table) {
            return@flatMap emptyList()
        }
        val declared = child.constraints
            .filter { it.type == ConstraintType.FOREIGN_KEY }
            .filter { it.references?.table == table }
            .filter { column == null || column in (it.references?.columns ?: emptyList()) }
            .map { InboundForeignKey(childName, it) }
        val fromColumns = child.columns.mapNotNull { (colName, col) ->
            col.references
                ?.takeIf { it.table == table && (column == null || it.column == column) }
                ?.let {
                    InboundForeignKey(
                        childName,
                        MssqlDiffObjectOps.columnForeignKey(childName, colName, it),
                        fromColumn = true,
                    )
                }
        }
        declared + fromColumns
    }.distinctBy(::keyOf)

    /** Zwei Fremdschluessel sind derselbe, wenn Kindtabelle und Name gleich sind. */
    fun keyOf(fk: InboundForeignKey): Pair<String, String> = fk.childTable to fk.constraint.name

    /**
     * Die eingehenden Fremdschluessel, die eine bereits gerenderte Operation
     * angelegt hat.
     *
     * Das Modell allein kann die Frage nicht beantworten: ob ein
     * Fremdschluessel JETZT dasteht, haengt daran, ob seine Operation schon
     * lief, nicht daran, in welchem Schema er steht. Wer sie ueberspringt,
     * laesst ihn beim `ALTER COLUMN` (Msg 5074) oder beim `DROP TABLE`
     * (Msg 3726) im Weg stehen.
     *
     * Es gibt **drei** Erzeuger, und sie unterscheiden sich darin, welche
     * Modellform sie rendern:
     *
     * - ein **Tabellen-Neubau** legt die Tabelle vollstaendig neu an, also
     *   BEIDE Formen — er ist richtungsunabhaengig, weil er auch abwaerts
     *   baut. Er steht deshalb in [rebuiltTables], nicht in [renderedBefore]:
     *   seine Operationen sagen nichts darueber, was er alles geschrieben hat.
     * - `CreateTable` rendert nur die Constraint-Liste
     *   ([InboundForeignKey.fromColumn] fällt weg, offener Punkt
     *   `mssql-column-level-foreign-keys.md`),
     * - `AddConstraint` genau seinen einen.
     *
     * Abwaerts kommt statt der letzten beiden genau eine hinzu: die Umkehr
     * eines `DropConstraint` ist ein `ADD`. Alle anderen Umkehrungen
     * entfernen.
     */
    fun materialisedBy(
        renderedBefore: List<DiffOperation>,
        rebuiltTables: Set<String>,
        schema: SchemaDefinition?,
        table: String,
        column: String?,
        direction: MssqlRenderDirection,
    ): List<InboundForeignKey> {
        val candidates = inboundForeignKeys(schema, table, column)
        if (direction == MssqlRenderDirection.DOWN) {
            val undoneDrops = renderedBefore.filterIsInstance<DiffOperation.DropConstraint>()
                .mapNotNull { op -> op.objectRef.path.firstOrNull()?.let { it to op.constraint.name } }
                .toSet()
            return candidates.filter { it.childTable in rebuiltTables || keyOf(it) in undoneDrops }
        }
        val createdTables = renderedBefore.filterIsInstance<DiffOperation.CreateTable>()
            .map { it.objectRef.rootName }
            .toSet()
        val addedConstraints = renderedBefore.filterIsInstance<DiffOperation.AddConstraint>()
            .mapNotNull { op -> op.objectRef.path.firstOrNull()?.let { it to op.constraint.name } }
            .toSet()
        return candidates.filter { fk ->
            fk.childTable in rebuiltTables ||
                (fk.childTable in createdTables && !fk.fromColumn) ||
                keyOf(fk) in addedConstraints
        }
    }

    /**
     * @param fromColumn `true`, wenn er aus einem `references` an einer Spalte
     *   stammt statt aus der Constraint-Liste. Der Unterschied ist nicht
     *   kosmetisch: `renderCreateTable` rendert nur die Constraint-Liste, ein
     *   spaltenlevel Fremdschluessel entsteht mit einer neuen Kindtabelle
     *   also NICHT (offener Punkt `mssql-column-level-foreign-keys.md`).
     */
    data class InboundForeignKey(
        val childTable: String,
        val constraint: ConstraintDefinition,
        val fromColumn: Boolean = false,
    )

    data class ColumnDependencies(
        val table: String,
        val column: String,
        val indices: List<IndexDefinition>,
        val constraints: List<ConstraintDefinition>,
        val hasColumnUnique: Boolean,
        val hasColumnReference: Boolean,
        val inPrimaryKey: Boolean,
        val inboundForeignKeys: List<InboundForeignKey>,
        private val surviving: TableDefinition?,
        private val survivingSchema: SchemaDefinition?,
    ) {
        val isEmpty: Boolean
            get() = indices.isEmpty() && constraints.isEmpty() && !hasColumnUnique &&
                !hasColumnReference && !inPrimaryKey && inboundForeignKeys.isEmpty()

        /** Regel 2: nur wiederherstellen, was auch nach der Aenderung noch gelten soll. */
        fun survivingIndices(): List<IndexDefinition> =
            indices.filter { idx -> surviving?.indices?.any { it.name == idx.name } == true }

        fun survivingConstraints(): List<ConstraintDefinition> =
            constraints.filter { c -> surviving?.constraints?.any { it.name == c.name } == true }

        fun survivingColumnUnique(): Boolean =
            hasColumnUnique && surviving?.columns?.get(column)?.unique == true

        /**
         * Das Gegenstueck zum `dropForeignKeyOnColumnSql` in [dropStatements].
         * Ohne diese Zeile raeumt der Spaltentanz einen spaltenlevel
         * Fremdschluessel ab und legt ihn nie wieder an — die Beziehung waere
         * nach der Migration still verschwunden.
         */
        fun survivingColumnReference(): ReferenceDefinition? =
            surviving?.columns?.get(column)?.references?.takeIf { hasColumnReference }

        fun survivingPrimaryKey(): List<String>? =
            surviving?.primaryKey?.takeIf { inPrimaryKey && it.isNotEmpty() }

        fun survivingInboundForeignKeys(): List<InboundForeignKey> {
            val surviving = inboundForeignKeys(survivingSchema, table, column).map(::keyOf).toSet()
            return inboundForeignKeys.filter { keyOf(it) in surviving }
        }

        companion object {
            val EMPTY = ColumnDependencies(
                table = "", column = "", indices = emptyList(), constraints = emptyList(),
                hasColumnUnique = false, hasColumnReference = false, inPrimaryKey = false,
                inboundForeignKeys = emptyList(), surviving = null, survivingSchema = null,
            )
        }
    }

    /**
     * Die Abraeum-Statements.
     *
     * SQL Server verweigert `ALTER COLUMN` und `DROP COLUMN` mit Msg 5074 nicht
     * nur wegen eines Defaults, sondern ebenso wegen eines abhaengigen CHECK,
     * eines UNIQUE-Constraints oder eines Index auf der Spalte — und d-migrates
     * eigener Generate-Pfad haengt an jede Enum-Spalte ein `ck_…` und an jede
     * `unique: true`-Spalte ein `uq_…`.
     *
     * Reihenfolge: erst die Fremdschluessel von aussen,
     * dann die Constraints der Tabelle, zuletzt die Indizes — ein Index, der
     * einen Constraint traegt, verschwindet mit ihm.
     */
    fun dropStatements(ctx: MssqlDiffRenderContext, deps: ColumnDependencies): List<String> {
        if (deps.isEmpty) return emptyList()
        val out = mutableListOf<String>()
        for (inbound in deps.inboundForeignKeys) {
            out += ctx.sql.dropConstraintSql(inbound.childTable, inbound.constraint.name)
        }
        for (constraint in deps.constraints) {
            out += ctx.sql.dropConstraintSql(deps.table, constraint.name)
        }
        if (deps.inPrimaryKey) out += ctx.sql.dropPrimaryKeySql(deps.table)
        if (deps.hasColumnUnique) out += ctx.sql.dropUniqueOnColumnSql(deps.table, deps.column)
        if (deps.hasColumnReference) out += ctx.sql.dropForeignKeyOnColumnSql(deps.table, deps.column)
        for (index in deps.indices) {
            out += ctx.sql.dropIndexSql(deps.table, index)
        }
        return out
    }

    /**
     * Die Wiederherstellungs-Statements, in umgekehrter Abhaengigkeitsrichtung:
     * erst Schluessel und Indizes der Tabelle, dann die Fremdschluessel, die
     * darauf zeigen. `null`, wenn eines davon nicht renderbar ist — dann ist
     * die Operation bereits als Blocker vermerkt.
     */
    fun recreateStatements(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        deps: ColumnDependencies,
    ): List<String>? {
        if (deps.isEmpty) return emptyList()
        val out = mutableListOf<String>()
        deps.survivingPrimaryKey()?.let { out += ctx.sql.addPrimaryKeySql(deps.table, it) }
        if (deps.survivingColumnUnique() && !skipLobUnique(op, ctx, deps)) {
            out += "ALTER TABLE ${ctx.sql.quote(deps.table)} ADD CONSTRAINT " +
                "${ctx.sql.quote(MssqlConstraintNames.unique(deps.table, deps.column))} " +
                "UNIQUE (${ctx.sql.quote(deps.column)});"
        }
        deps.survivingColumnReference()?.let { ref ->
            out += MssqlDiffObjectOps.resolveConstraintSql(
                op, ctx, deps.table, MssqlDiffObjectOps.columnForeignKey(deps.table, deps.column, ref),
            ) ?: return null
        }
        for (index in deps.survivingIndices()) {
            out += MssqlDiffObjectOps.resolveIndexSql(op, ctx, deps.table, index) ?: return null
        }
        for (constraint in deps.survivingConstraints()) {
            out += MssqlDiffObjectOps.resolveConstraintSql(op, ctx, deps.table, constraint) ?: return null
        }
        for (inbound in deps.survivingInboundForeignKeys()) {
            out += MssqlDiffObjectOps
                .resolveConstraintSql(op, ctx, inbound.childTable, inbound.constraint) ?: return null
        }
        return out
    }

    /**
     * Eine Spalte, die nach der Aenderung ein LOB ist (`NVARCHAR(MAX)` &c.),
     * kann keine Schluesselspalte mehr sein — SQL Server lehnt das UNIQUE mit
     * Msg 1919 ab. Der Generate-Pfad laesst es in dem Fall weg und meldet
     * `E057`; der Diff tut dasselbe, statt ein Statement zu schicken, das
     * sicher scheitert.
     */
    private fun skipLobUnique(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        deps: ColumnDependencies,
    ): Boolean {
        val schema = ctx.schemaForDirection() ?: return false
        val col = schema.tables[deps.table]?.columns?.get(deps.column) ?: return false
        if (ctx.sql.isKeyEligible(col, schema)) return false
        ctx.warning(
            op,
            "The UNIQUE guarantee on '${deps.table}.${deps.column}' is not restored: after the change the " +
                "column renders as a large object, which SQL Server does not allow as a key column (Msg 1919).",
            code = "E057",
        )
        return true
    }
}
