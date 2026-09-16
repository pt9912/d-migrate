package dev.dmigrate.mcp.registry

import dev.dmigrate.cli.commands.CompareSignature
import dev.dmigrate.cli.commands.SchemaFindingPath
import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.mcp.registry.CompareFinding.added
import dev.dmigrate.mcp.registry.CompareFinding.beforeAfter
import dev.dmigrate.mcp.registry.CompareFinding.changed
import dev.dmigrate.mcp.registry.CompareFinding.finding
import dev.dmigrate.mcp.registry.CompareFinding.removed
import dev.dmigrate.mcp.schema.SchemaFindingSeverity

/** Die Funde einer geaenderten Tabelle — ein Fund je Aenderung. */
internal object TableCompareFindings {

    /**
     * Walks a [TableDiff] into one finding per change instead of a
     * single coarse `TABLE_CHANGED` entry. Severity policy:
     * - column added → info; column removed → error.
     * - column type change → error (incompatible read).
     * - `required` flipped to true → error (existing rows may
     *   violate); flipped to false → info (relaxation).
     * - `unique` tightened → warning (existing dupes block); relaxed
     *   → info.
     * - `default` / `references` change → warning (semantic drift).
     * - primary key change → error (identity contract change).
     * - index added → info; removed/changed → warning.
     * - constraint added/changed → warning; removed → info.
     * - metadata change → info.
     *
     * Codes share the `TABLE_…` prefix and a dotted path
     * (`tables.t1.columns.c1`, see `SchemaFindingPath`) so clients can
     * filter or roll-up findings without parsing free-form messages.
     */
    fun of(diff: TableDiff): List<Map<String, Any?>> = buildList {
        val tablePath = SchemaFindingPath.table(diff.name)
        diff.columnsAdded.forEach { (col, _) ->
            add(added("TABLE_COLUMN_ADDED", SchemaFindingPath.column(diff.name, col)))
        }
        diff.columnsRemoved.forEach { (col, _) ->
            val colPath = SchemaFindingPath.column(diff.name, col)
            add(finding(SchemaFindingSeverity.ERROR, "TABLE_COLUMN_REMOVED", colPath, "$colPath was removed"))
        }
        diff.columnsChanged.forEach { addAll(columnFindings(SchemaFindingPath.column(diff.name, it.name), it)) }
        diff.primaryKey?.let {
            add(
                finding(
                    SchemaFindingSeverity.ERROR,
                    "TABLE_PRIMARY_KEY_CHANGED",
                    SchemaFindingPath.field(tablePath, "primary_key"),
                    "primary key changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
        // Indizes und Constraints vergleicht der Comparator als Ganzes; der
        // Fund nennt sie deshalb mit ihrer Kurzform, die jedes gewertete Feld
        // traegt (CompareSignature) — sonst stuende ein geaenderter CHECK
        // beidseitig gleich da. Die Kurzform ist nie leer, `details` fehlt nie.
        diff.indicesAdded.forEach {
            add(added("TABLE_INDEX_ADDED", SchemaFindingPath.index(diff.name, indexKey(it)), after(CompareSignature.index(it))))
        }
        diff.indicesRemoved.forEach {
            add(
                removed(
                    "TABLE_INDEX_REMOVED", SchemaFindingPath.index(diff.name, indexKey(it)),
                    before(CompareSignature.index(it)),
                ),
            )
        }
        diff.indicesChanged.forEach {
            add(
                changed(
                    "TABLE_INDEX_CHANGED", SchemaFindingPath.index(diff.name, indexKey(it.before)),
                    beforeAfter(CompareSignature.index(it.before), CompareSignature.index(it.after)),
                ),
            )
        }
        diff.constraintsAdded.forEach {
            // Tightening: a new constraint may reject pre-existing
            // rows; surface as warning, not info.
            add(
                changed(
                    "TABLE_CONSTRAINT_ADDED", SchemaFindingPath.constraint(diff.name, it.name),
                    after(CompareSignature.constraint(it)),
                ),
            )
        }
        diff.constraintsRemoved.forEach {
            add(
                finding(
                    SchemaFindingSeverity.INFO,
                    "TABLE_CONSTRAINT_REMOVED",
                    SchemaFindingPath.constraint(diff.name, it.name),
                    "constraint ${it.name} was removed",
                    before(CompareSignature.constraint(it)),
                ),
            )
        }
        diff.constraintsChanged.forEach {
            add(
                changed(
                    "TABLE_CONSTRAINT_CHANGED", SchemaFindingPath.constraint(diff.name, it.before.name),
                    beforeAfter(CompareSignature.constraint(it.before), CompareSignature.constraint(it.after)),
                ),
            )
        }
        diff.metadata?.let {
            add(
                finding(
                    SchemaFindingSeverity.INFO,
                    "TABLE_METADATA_CHANGED",
                    SchemaFindingPath.field(tablePath, "metadata"),
                    "table metadata changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
    }

    private fun columnFindings(colPath: String, diff: ColumnDiff): List<Map<String, Any?>> = buildList {
        diff.type?.let {
            add(
                finding(
                    SchemaFindingSeverity.ERROR,
                    "TABLE_COLUMN_TYPE_CHANGED",
                    SchemaFindingPath.field(colPath, "type"),
                    "type changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
        diff.required?.let { add(requiredFinding(colPath, it)) }
        diff.unique?.let { add(uniqueFinding(colPath, it)) }
        diff.default?.let {
            add(
                finding(
                    SchemaFindingSeverity.WARNING,
                    "TABLE_COLUMN_DEFAULT_CHANGED",
                    SchemaFindingPath.field(colPath, "default"),
                    "default changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
        diff.references?.let {
            add(
                finding(
                    SchemaFindingSeverity.WARNING,
                    "TABLE_COLUMN_REFERENCES_CHANGED",
                    SchemaFindingPath.field(colPath, "references"),
                    "foreign-key references changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
        diff.generation?.let {
            add(
                finding(
                    SchemaFindingSeverity.WARNING,
                    "TABLE_COLUMN_GENERATION_CHANGED",
                    SchemaFindingPath.field(colPath, "generation"),
                    "generation changed from ${it.before} to ${it.after}",
                    beforeAfter(it.before, it.after),
                ),
            )
        }
    }

    private fun requiredFinding(colPath: String, change: ValueChange<Boolean>): Map<String, Any?> =
        if (!change.before && change.after) {
            // false → true: pre-existing rows may carry NULL; tightening is breaking.
            finding(
                SchemaFindingSeverity.ERROR,
                "TABLE_COLUMN_REQUIRED_TIGHTENED",
                SchemaFindingPath.field(colPath, "required"),
                "column became required",
                beforeAfter(false, true),
            )
        } else {
            finding(
                SchemaFindingSeverity.INFO,
                "TABLE_COLUMN_REQUIRED_RELAXED",
                SchemaFindingPath.field(colPath, "required"),
                "column is no longer required",
                beforeAfter(true, false),
            )
        }

    private fun uniqueFinding(colPath: String, change: ValueChange<Boolean>): Map<String, Any?> =
        if (!change.before && change.after) {
            // false → true: pre-existing duplicates would block the
            // unique index — warning rather than error so clients can
            // still ship after a data audit.
            finding(
                SchemaFindingSeverity.WARNING,
                "TABLE_COLUMN_UNIQUE_TIGHTENED",
                SchemaFindingPath.field(colPath, "unique"),
                "column became unique",
                beforeAfter(false, true),
            )
        } else {
            finding(
                SchemaFindingSeverity.INFO,
                "TABLE_COLUMN_UNIQUE_RELAXED",
                SchemaFindingPath.field(colPath, "unique"),
                "column is no longer unique",
                beforeAfter(true, false),
            )
        }

    private fun before(signature: String): Map<String, String> = mapOf("before" to signature)

    private fun after(signature: String): Map<String, String> = mapOf("after" to signature)

    /** Ein Index unter seinem Namen — ein unbenannter unter seinen Schluesseln. */
    private fun indexKey(index: IndexDefinition): String = index.name ?: index.columns.joinToString(",")
}
