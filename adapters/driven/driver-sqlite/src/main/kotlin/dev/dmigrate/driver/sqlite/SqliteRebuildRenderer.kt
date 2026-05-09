package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlStatement

/**
 * Synthesises the SQLite RebuildTable sequence per
 * `docs/planning/open/diffresult-migration-plan.md §6.4`. Given a
 * bucket of rebuild-required operations on one table plus a
 * `source` [TableDefinition] (the table state to copy data FROM) and
 * a `target` [TableDefinition] (the target table shape), emits:
 *
 * 1. `PRAGMA foreign_keys = OFF;`
 * 2. `BEGIN IMMEDIATE;`
 * 3. `CREATE TABLE <temp> (...target columns/PK/constraints...);`
 * 4. `INSERT INTO <temp> (cols...) SELECT (mapped exprs...) FROM <orig>;`
 * 5. `DROP TABLE <orig>;`
 * 6. `ALTER TABLE <temp> RENAME TO <orig>;`
 * 7. `CREATE INDEX ...` for every index in the target table.
 * 8. `PRAGMA foreign_key_check;`
 * 9. `COMMIT;`
 * 10. `PRAGMA foreign_keys = ON;`
 *
 * All statements are tagged with the bucket's union of operation
 * IDs so a runner failure can attribute back to *all* the business
 * ops the rebuild covers.
 *
 * Direction-agnostic: the renderer takes `(source, target)` and
 * applies the canonical sequence. The dispatcher
 * ([SqliteDiffDdlGenerator]) chooses which schema is source vs.
 * target based on Up/Down direction:
 *
 * - **Up**: `source = currentSchema`, `target = desiredSchema`.
 * - **Down**: `source = desiredSchema`, `target = currentSchema`.
 *
 * Down also enforces a reversibility precondition before calling —
 * buckets containing any `NOT_REVERSIBLE` operation are short-
 * circuited to `ROLLBACK_NOT_POSSIBLE`.
 *
 * Column-mapping rules (apply identically in both directions; the
 * meaning of "added" / "dropped" is relative to the target):
 *
 * - Columns present in both `source` and `target`: emitted as-is
 *   (`"col"`); type-changed columns emit `CAST("col" AS <newType>)`.
 * - Columns added in the target side: filled with the column's
 *   `DEFAULT` literal if any, else `NULL` for nullable, else this
 *   blocks the rebuild with `NOT_NULL_BACKFILL_REQUIRED`.
 * - Columns dropped on the target side: simply not selected.
 */
internal class SqliteRebuildRenderer(
    private val sql: SqliteDiffSqlBuilders,
) {

    fun renderRebuild(
        table: String,
        bucket: List<DiffOperation>,
        source: TableDefinition,
        target: TableDefinition,
        ctx: SqliteDiffRenderContext,
    ) {
        val mapping = computeColumnMapping(source, target)
        if (mapping.notNullBackfillBlocked.isNotEmpty()) {
            for (col in mapping.notNullBackfillBlocked) {
                ctx.addDiagnostic(
                    DiffDiagnostic(
                        code = "NOT_NULL_BACKFILL_REQUIRED",
                        message = "RebuildTable for `$table` cannot fill new NOT NULL column " +
                            "`$col` automatically — the column has no default and the existing " +
                            "rows have no source value. Either add a default to the schema, " +
                            "make the column nullable, or supply a manual data-migration step.",
                        severity = DiffDiagnostic.Severity.BLOCKER,
                    ),
                )
            }
            ctx.addBlocker(
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                operationIds = bucket.map { it.id }.toSet(),
            )
            for (op in bucket) ctx.markRendered(op)
            return
        }
        emitRebuildSequence(table, bucket, target, mapping, ctx)
    }

    private fun emitRebuildSequence(
        table: String,
        bucket: List<DiffOperation>,
        target: TableDefinition,
        mapping: ColumnMapping,
        ctx: SqliteDiffRenderContext,
    ) {
        val opIds = bucket.map { it.id }.toSet()
        val tempName = SqliteRebuildPlanner.tempTableName(table, bucket)

        ctx.emitRebuildStatement("PRAGMA foreign_keys = OFF;", opIds)
        ctx.emitRebuildStatement("BEGIN IMMEDIATE;", opIds)
        ctx.emitRebuildStatement(buildCreateTempSql(tempName, target), opIds)
        ctx.emitRebuildStatement(
            buildInsertSelectSql(tempName, table, mapping),
            opIds,
        )
        ctx.emitRebuildStatement("DROP TABLE ${sql.quote(table)};", opIds)
        ctx.emitRebuildStatement(
            "ALTER TABLE ${sql.quote(tempName)} RENAME TO ${sql.quote(table)};",
            opIds,
        )
        for (idx in target.indices) {
            ctx.emitRebuildStatement(sql.createIndexSql(table, idx), opIds)
        }
        ctx.emitRebuildStatement("PRAGMA foreign_key_check;", opIds)
        ctx.emitRebuildStatement("COMMIT;", opIds)
        ctx.emitRebuildStatement("PRAGMA foreign_keys = ON;", opIds)

        for (op in bucket) ctx.markRendered(op)
        // The rebuild is destructive (it copies and drops data) and requires manual review.
        ctx.markBucketDestructive(opIds)
    }

    private fun buildCreateTempSql(tempName: String, target: TableDefinition): String {
        val lines = mutableListOf<String>()
        for ((colName, col) in target.columns.entries.sortedBy { it.key }) {
            lines += "    " + sql.columnLine(colName, col)
        }
        if (target.primaryKey.isNotEmpty()) {
            lines += "    PRIMARY KEY (" + target.primaryKey.joinToString(", ") { sql.quote(it) } + ")"
        }
        for (c in target.constraints.sortedBy { it.name }) {
            sql.constraintLine(c)?.let { lines += "    $it" }
        }
        return buildString {
            append("CREATE TABLE ").append(sql.quote(tempName)).append(" (\n")
            append(lines.joinToString(",\n"))
            append("\n);")
        }
    }

    private fun buildInsertSelectSql(tempName: String, originalTable: String, mapping: ColumnMapping): String {
        val targetCols = mapping.entries.joinToString(", ") { sql.quote(it.targetName) }
        val selectExprs = mapping.entries.joinToString(", ") { it.selectExpression }
        return "INSERT INTO ${sql.quote(tempName)} ($targetCols) " +
            "SELECT $selectExprs FROM ${sql.quote(originalTable)};"
    }

    private fun computeColumnMapping(source: TableDefinition, target: TableDefinition): ColumnMapping {
        val entries = mutableListOf<ColumnMappingEntry>()
        val blocked = mutableListOf<String>()
        for ((name, targetCol) in target.columns.entries.sortedBy { it.key }) {
            val currentCol = source.columns[name]
            entries += when {
                currentCol != null && currentCol.type == targetCol.type ->
                    ColumnMappingEntry(name, sql.quote(name))
                currentCol != null ->
                    ColumnMappingEntry(name, "CAST(${sql.quote(name)} AS ${sql.toSql(targetCol.type)})")
                targetCol.default != null ->
                    ColumnMappingEntry(name, defaultLiteral(targetCol))
                !targetCol.required ->
                    ColumnMappingEntry(name, "NULL")
                else -> {
                    blocked += name
                    ColumnMappingEntry(name, "/* unfilled */")
                }
            }
        }
        return ColumnMapping(entries = entries, notNullBackfillBlocked = blocked)
    }

    private fun defaultLiteral(col: ColumnDefinition): String {
        val dv = col.default ?: return "NULL"
        return when (dv) {
            is DefaultValue.StringLiteral -> "'${dv.value.replace("'", "''")}'"
            is DefaultValue.NumberLiteral -> dv.value.toString()
            is DefaultValue.BooleanLiteral -> if (dv.value) "1" else "0"
            is DefaultValue.FunctionCall -> if (dv.name == "current_timestamp") "CURRENT_TIMESTAMP"
                else "${dv.name}()"
            is DefaultValue.SequenceNextVal -> "NULL" // SQLite has no sequences in the first matrix
        }
    }

    private data class ColumnMappingEntry(val targetName: String, val selectExpression: String)
    private data class ColumnMapping(
        val entries: List<ColumnMappingEntry>,
        val notNullBackfillBlocked: List<String>,
    )
}
