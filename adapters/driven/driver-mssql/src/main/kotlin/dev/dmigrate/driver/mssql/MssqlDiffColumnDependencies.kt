package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
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
     */
    fun of(
        table: String,
        column: String,
        bearing: SchemaDefinition?,
        surviving: SchemaDefinition?,
    ): ColumnDependencies {
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
            inboundForeignKeys = inboundForeignKeys(bearing, table, column),
            surviving = survivingTable,
            survivingSchema = surviving,
        )
    }

    /**
     * Fremdschluessel **anderer** Tabellen auf diese Spalte. SQL Server lehnt
     * `ALTER COLUMN` auch dann mit Msg 5074 ab, wenn die Abhaengigkeit von
     * aussen kommt — die eigene Tabelle allein zu betrachten reicht nicht.
     */
    private fun inboundForeignKeys(
        schema: SchemaDefinition,
        table: String,
        column: String,
    ): List<InboundForeignKey> = schema.tables.flatMap { (childName, child) ->
        if (childName == table) {
            emptyList()
        } else {
            child.constraints
                .filter { it.type == ConstraintType.FOREIGN_KEY }
                .filter { it.references?.table == table && column in (it.references?.columns ?: emptyList()) }
                .map { InboundForeignKey(childName, it) }
        }
    }

    data class InboundForeignKey(val childTable: String, val constraint: ConstraintDefinition)

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

        fun survivingPrimaryKey(): List<String>? =
            surviving?.primaryKey?.takeIf { inPrimaryKey && it.isNotEmpty() }

        fun survivingInboundForeignKeys(): List<InboundForeignKey> = inboundForeignKeys.filter { inbound ->
            survivingSchema?.tables?.get(inbound.childTable)
                ?.constraints?.any { it.name == inbound.constraint.name } == true
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
     * Die Abraeum-Statements. Reihenfolge: erst die Fremdschluessel von aussen,
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
