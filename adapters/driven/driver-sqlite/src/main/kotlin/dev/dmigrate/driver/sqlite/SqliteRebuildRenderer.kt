package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Synthesises the SQLite RebuildTable sequence per
 * `docs/planning/in-progress/diffresult-migration-plan.md §6.4`. Given a
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
 * **Runner contract for the canonical sequence (Phase F):**
 *
 * The 9-statement sequence emits its own transaction control via
 * `BEGIN IMMEDIATE;` / `COMMIT;`. Runners executing this output
 * MUST:
 *
 * - disable the JDBC connection's auto-commit mode for the duration
 *   of the rebuild (otherwise xerial-sqlite wraps each
 *   `executeUpdate` in an implicit BEGIN/COMMIT, which collides with
 *   the explicit `BEGIN IMMEDIATE;`);
 * - NOT wrap the canonical sequence in an outer transaction — the
 *   `COMMIT;` in step 8 would close the outer transaction, leaving
 *   `PRAGMA foreign_keys = ON` to run outside any transaction;
 * - treat the [DiffPhase] tag (`PREPARE` / `TABLES` / `INDEXES` /
 *   `CLEANUP`) as a structural marker rather than parsing the SQL
 *   string. A future `transactionScope` field on
 *   [dev.dmigrate.driver.migration.MigrationDdlStatement] is the
 *   long-term fix; until then the phase tag is the canonical signal.
 *
 * **Silent drops in the rebuild (acknowledged carve-out):**
 *
 * - User-defined triggers attached to the rebuilt table are dropped
 *   by the `DROP TABLE` step and not recreated. Triggers belong to
 *   the schema definition; if they're not in the diff they will not
 *   reappear. Future Phase F work: re-create from
 *   `target.triggers.filter { it.table == table }`.
 * - Indices created on the original table that aren't in
 *   `target.indices` are silently dropped (system indices, ad-hoc
 *   `CREATE INDEX` outside the schema). Same future-work caveat.
 * - FKs in *child* tables that reference the rebuilt table are kept
 *   (they live in the child's CREATE TABLE), but
 *   `PRAGMA foreign_key_check` (step 8) catches inconsistencies if
 *   the rebuild removes a referenced column.
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
        if (mapping.notNullBackfillBlocked.isNotEmpty() || mapping.castNotWhitelisted.isNotEmpty()) {
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
            for (block in mapping.castNotWhitelisted) {
                ctx.addDiagnostic(
                    DiffDiagnostic(
                        code = "SQLITE_CAST_NOT_WHITELISTED",
                        message = "RebuildTable for `$table` cannot CAST column `${block.column}` " +
                            "from ${block.source} to ${block.target} automatically — " +
                            "${SqliteCastMatrix.describeBlock(block.source, block.target)}. " +
                            "Keep the type, change the schema to a whitelisted target, or " +
                            "supply a manual data-migration step.",
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
        val bucketRisk = ctx.bucketRisk(bucket)
        val safe = dev.dmigrate.core.diff.migration.OperationRisk.SAFE

        // PREPARE phase: PRAGMA wrap + BEGIN. SAFE risk — these don't touch data.
        ctx.emitRebuildStatement("PRAGMA foreign_keys = OFF;", opIds, risk = safe, phase = DiffPhase.PREPARE)
        ctx.emitRebuildStatement("BEGIN IMMEDIATE;", opIds, risk = safe, phase = DiffPhase.PREPARE)

        // TABLES phase: schema reshape. Statements that touch data inherit bucketRisk;
        // CREATE temp is structurally safe (no data yet).
        ctx.emitRebuildStatement(buildCreateTempSql(tempName, target), opIds, risk = safe, phase = DiffPhase.TABLES)
        ctx.emitRebuildStatement(
            buildInsertSelectSql(tempName, table, mapping),
            opIds, risk = bucketRisk, phase = DiffPhase.TABLES,
        )
        ctx.emitRebuildStatement(
            "DROP TABLE ${sql.quote(table)};",
            opIds, risk = bucketRisk, phase = DiffPhase.TABLES,
        )
        ctx.emitRebuildStatement(
            "ALTER TABLE ${sql.quote(tempName)} RENAME TO ${sql.quote(table)};",
            opIds, risk = safe, phase = DiffPhase.TABLES,
        )

        // INDEXES phase: re-create indices on the renamed-back table.
        for (idx in target.indices) {
            ctx.emitRebuildStatement(sql.createIndexSql(table, idx), opIds, risk = safe, phase = DiffPhase.INDEXES)
        }

        // CLEANUP phase: integrity check + commit + re-enable FKs.
        ctx.emitRebuildStatement("PRAGMA foreign_key_check;", opIds, risk = safe, phase = DiffPhase.CLEANUP)
        ctx.emitRebuildStatement("COMMIT;", opIds, risk = safe, phase = DiffPhase.CLEANUP)
        ctx.emitRebuildStatement("PRAGMA foreign_keys = ON;", opIds, risk = safe, phase = DiffPhase.CLEANUP)

        for (op in bucket) ctx.markRendered(op)
        ctx.applyBucketRisk(opIds, bucketRisk)
    }

    private fun buildCreateTempSql(tempName: String, target: TableDefinition): String {
        val lines = mutableListOf<String>()
        for ((colName, col) in targetColumnOrder(target)) {
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

    /**
     * Single source of truth for the column iteration order across
     * `CREATE TABLE <temp>` and `INSERT INTO <temp> ... SELECT ...`.
     * Both paths walk this list, so the column lists in the CREATE
     * and the (cols)/expressions in the INSERT-SELECT cannot drift.
     */
    private fun targetColumnOrder(target: TableDefinition): List<Pair<String, ColumnDefinition>> =
        target.columns.entries.sortedBy { it.key }.map { it.key to it.value }

    private fun buildInsertSelectSql(tempName: String, originalTable: String, mapping: ColumnMapping): String {
        val targetCols = mapping.entries.joinToString(", ") { sql.quote(it.targetName) }
        val selectExprs = mapping.entries.joinToString(", ") { it.selectExpression }
        return "INSERT INTO ${sql.quote(tempName)} ($targetCols) " +
            "SELECT $selectExprs FROM ${sql.quote(originalTable)};"
    }

    private fun computeColumnMapping(source: TableDefinition, target: TableDefinition): ColumnMapping {
        val entries = mutableListOf<ColumnMappingEntry>()
        val blocked = mutableListOf<String>()
        val castBlocks = mutableListOf<CastBlock>()
        for ((name, targetCol) in targetColumnOrder(target)) {
            val currentCol = source.columns[name]
            entries += when {
                currentCol != null && currentCol.type == targetCol.type ->
                    ColumnMappingEntry(name, sql.quote(name))
                currentCol != null -> {
                    if (!SqliteCastMatrix.isWhitelisted(currentCol.type, targetCol.type)) {
                        castBlocks += CastBlock(name, currentCol.type, targetCol.type)
                        ColumnMappingEntry(name, "/* unsafe cast */")
                    } else {
                        ColumnMappingEntry(name, "CAST(${sql.quote(name)} AS ${sql.toSql(targetCol.type)})")
                    }
                }
                targetCol.default is DefaultValue.SequenceNextVal -> {
                    // SQLite has no sequences. Routing this to NULL would silently fill the column;
                    // surface it as NOT_NULL_BACKFILL_REQUIRED so the runner sees the conflict.
                    blocked += name
                    ColumnMappingEntry(name, "/* sequence-default not supported */")
                }
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
        return ColumnMapping(
            entries = entries,
            notNullBackfillBlocked = blocked,
            castNotWhitelisted = castBlocks,
        )
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
    private data class CastBlock(val column: String, val source: NeutralType, val target: NeutralType)
    private data class ColumnMapping(
        val entries: List<ColumnMappingEntry>,
        val notNullBackfillBlocked: List<String>,
        val castNotWhitelisted: List<CastBlock>,
    )
}
