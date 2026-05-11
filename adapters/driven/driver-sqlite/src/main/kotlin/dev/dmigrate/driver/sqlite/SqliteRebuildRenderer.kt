package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Renders the SQLite RebuildTable SQL sequence from a pre-built
 * [SqliteRebuildPlan] (Phase H.1b). Pure consumption — the plan
 * carries every input the renderer needs (column mapping, temp
 * name, indices, dependent views/triggers, preflight, bucket risk,
 * source-operation-ids).
 *
 * Canonical emitted sequence per Plan §6.4:
 *
 * 1. `PRAGMA foreign_keys = OFF;`
 * 2. `BEGIN IMMEDIATE;`
 * 3. `CREATE TABLE <temp> (...target columns/PK/constraints...);`
 * 4. `INSERT INTO <temp> (cols...) SELECT (mapped exprs...) FROM <orig>;`
 * 5. `DROP TABLE <orig>;`
 * 6. `ALTER TABLE <temp> RENAME TO <orig>;`
 * 7. `CREATE INDEX ...` for every index in [SqliteRebuildPlan.indexesToRecreate].
 * 8. `PRAGMA foreign_key_check;`
 * 9. `COMMIT;`
 * 10. `PRAGMA foreign_keys = ON;`
 *
 * All statements are tagged with [SqliteRebuildPlan.sourceOperationIds]
 * so a runner failure can attribute back to *all* the business ops the
 * rebuild covers.
 *
 * **Runner contract for the canonical sequence (Phase F):**
 *
 * The 9-statement sequence emits its own transaction control via
 * `BEGIN IMMEDIATE;` / `COMMIT;`. Runners executing this output MUST:
 *
 * - disable the JDBC connection's auto-commit mode for the duration of
 *   the rebuild (otherwise xerial-sqlite wraps each `executeUpdate` in
 *   an implicit BEGIN/COMMIT, which collides with the explicit
 *   `BEGIN IMMEDIATE;`);
 * - NOT wrap the canonical sequence in an outer transaction — the
 *   `COMMIT;` in step 8 would close the outer transaction, leaving
 *   `PRAGMA foreign_keys = ON` to run outside any transaction;
 * - treat the [DiffPhase] tag (`PREPARE` / `TABLES` / `INDEXES` /
 *   `CLEANUP`) as a structural marker rather than parsing the SQL
 *   string.
 *
 * **Silent drops still present at H.1b** (will be addressed by H.3):
 *
 * - User-defined triggers attached to the rebuilt table are dropped by
 *   the `DROP TABLE` step and not recreated.
 * - Indices on the original table that aren't in [newTable].indices
 *   are silently dropped (system indices, ad-hoc `CREATE INDEX`
 *   outside the schema).
 * - FKs in *child* tables that reference the rebuilt table are kept
 *   (they live in the child's CREATE TABLE), but
 *   `PRAGMA foreign_key_check` (step 8) catches inconsistencies if
 *   the rebuild removes a referenced column.
 *
 * Column-mapping rules (apply identically in both directions; the
 * meaning of "added" / "dropped" is relative to the target):
 *
 * - Columns present in both `source` and `target`: emitted as-is
 *   (`"col"`); type-changed columns emit `CAST("col" AS <newType>)`
 *   subject to [SqliteCastMatrix].
 * - Columns added in the target side: filled with the column's
 *   `DEFAULT` literal if any, else `NULL` for nullable, else this
 *   blocks the rebuild with `NOT_NULL_BACKFILL_REQUIRED`.
 * - Columns dropped on the target side: simply not selected.
 */
internal class SqliteRebuildRenderer(
    private val sql: SqliteDiffSqlBuilders,
) {

    /**
     * Phase H.1b entry point: render the canonical RebuildTable
     * sequence from a pre-built [SqliteRebuildPlan]. Pure consumption.
     */
    fun render(plan: SqliteRebuildPlan, ctx: SqliteDiffRenderContext) {
        if (plan.mapping.isBlocked) {
            emitBlockerDiagnostics(plan, ctx)
            return
        }
        emitRebuildSequence(plan, ctx)
    }

    private fun emitBlockerDiagnostics(plan: SqliteRebuildPlan, ctx: SqliteDiffRenderContext) {
        for (col in plan.mapping.notNullBackfillBlocked) {
            ctx.addDiagnostic(
                DiffDiagnostic(
                    code = "NOT_NULL_BACKFILL_REQUIRED",
                    message = "RebuildTable for `${plan.originalTableName}` cannot fill new NOT NULL " +
                        "column `$col` automatically — the column has no default and the existing " +
                        "rows have no source value. Either add a default to the schema, " +
                        "make the column nullable, or supply a manual data-migration step.",
                    severity = DiffDiagnostic.Severity.BLOCKER,
                ),
            )
        }
        for (block in plan.mapping.castNotWhitelisted) {
            ctx.addDiagnostic(
                DiffDiagnostic(
                    code = "SQLITE_CAST_NOT_WHITELISTED",
                    message = "RebuildTable for `${plan.originalTableName}` cannot CAST column " +
                        "`${block.column}` from ${block.source} to ${block.target} automatically — " +
                        "${SqliteCastMatrix.describeBlock(block.source, block.target)}. " +
                        "Keep the type, change the schema to a whitelisted target, or " +
                        "supply a manual data-migration step.",
                    severity = DiffDiagnostic.Severity.BLOCKER,
                ),
            )
        }
        ctx.addBlocker(
            MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            operationIds = plan.sourceOperationIds,
        )
        for (op in plan.bucketOperations) ctx.markRendered(op)
    }

    private fun emitRebuildSequence(plan: SqliteRebuildPlan, ctx: SqliteDiffRenderContext) {
        val opIds = plan.sourceOperationIds
        val tempName = plan.newTableTempName
        val originalTable = plan.originalTableName
        val bucketRisk = plan.risk
        val safe = OperationRisk.SAFE

        // PREPARE phase: PRAGMA wrap + BEGIN. SAFE risk — these don't touch data.
        // Phase H.3b: in EXECUTE mode emit a runner-hook marker so the
        // d-migrate runner reads the prior FK state before this sequence.
        // STANDALONE mode keeps the canonical 9-statement output bit-
        // identical to pre-H.3b — the "prior OFF state not restored"
        // warning lives in the artefact-file header at the CLI layer
        // (`schema migrate --plan-only` output), not in the per-statement
        // stream.
        if (plan.emissionMode == SqliteRebuildEmissionMode.EXECUTE) {
            ctx.emitRebuildStatement(
                "-- dmigrate:runner-hook=save-fk-state-before-pragma-off",
                opIds, risk = safe, phase = DiffPhase.PREPARE,
            )
        }
        ctx.emitRebuildStatement("PRAGMA foreign_keys = OFF;", opIds, risk = safe, phase = DiffPhase.PREPARE)
        ctx.emitRebuildStatement("BEGIN IMMEDIATE;", opIds, risk = safe, phase = DiffPhase.PREPARE)

        // TABLES phase: schema reshape. Statements that touch data inherit bucketRisk;
        // CREATE temp is structurally safe (no data yet).
        ctx.emitRebuildStatement(
            buildCreateTempSql(tempName, plan.newTable),
            opIds, risk = safe, phase = DiffPhase.TABLES,
        )
        ctx.emitRebuildStatement(
            buildInsertSelectSql(tempName, originalTable, plan.mapping),
            opIds, risk = bucketRisk, phase = DiffPhase.TABLES,
        )
        // Phase H.3a: drop dependent triggers and views BEFORE the
        // table drop. Triggers first (they reference the table
        // directly); views next (some views may reference triggers
        // via SQLite's INSTEAD-OF mechanism, so views go second).
        for (named in plan.dependentTriggersToDrop) {
            ctx.emitRebuildStatement(
                "DROP TRIGGER IF EXISTS ${sql.quote(named.name)};",
                opIds, risk = safe, phase = DiffPhase.TABLES,
            )
        }
        for (named in plan.dependentViewsToDrop) {
            ctx.emitRebuildStatement(
                "DROP VIEW IF EXISTS ${sql.quote(named.name)};",
                opIds, risk = safe, phase = DiffPhase.TABLES,
            )
        }
        ctx.emitRebuildStatement(
            "DROP TABLE ${sql.quote(originalTable)};",
            opIds, risk = bucketRisk, phase = DiffPhase.TABLES,
        )
        ctx.emitRebuildStatement(
            "ALTER TABLE ${sql.quote(tempName)} RENAME TO ${sql.quote(originalTable)};",
            opIds, risk = safe, phase = DiffPhase.TABLES,
        )

        // INDEXES phase: re-create indices on the renamed-back table.
        for (idx in plan.indexesToRecreate) {
            ctx.emitRebuildStatement(
                sql.createIndexSql(originalTable, idx),
                opIds, risk = safe, phase = DiffPhase.INDEXES,
            )
        }

        // Phase H.3a: recreate dependent views and triggers AFTER the
        // RENAME so they see the post-rebuild table shape. Views
        // first (triggers may reference views), triggers next.
        for (named in plan.dependentViewsToRecreate) {
            ctx.emitRebuildStatement(
                sql.createViewSql(named.name, named.definition),
                opIds, risk = safe, phase = DiffPhase.INDEXES,
            )
        }
        for (named in plan.dependentTriggersToRecreate) {
            val triggerSql = sql.createTriggerSql(named.name, named.definition)
            if (triggerSql == null) {
                // Trigger body missing or non-SQLite source dialect; the
                // recreate would produce malformed SQL. Surface as a
                // BLOCKER but continue emitting the rebuild — the
                // pre-recreate steps are still correct, the operator
                // must supply a body before re-running.
                ctx.addDiagnostic(
                    dev.dmigrate.core.diff.migration.DiffDiagnostic(
                        code = "SQLITE_REBUILD_TRIGGER_NOT_RENDERABLE",
                        message = "RebuildTable for `$originalTable` cannot recreate trigger " +
                            "`${named.name}` automatically — the trigger has no body or a " +
                            "non-SQLite sourceDialect. Supply a SQLite-compatible body in the " +
                            "schema definition.",
                        severity = dev.dmigrate.core.diff.migration.DiffDiagnostic.Severity.BLOCKER,
                    ),
                )
                ctx.addBlocker(
                    dev.dmigrate.driver.migration.MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                    operationIds = opIds,
                )
                continue
            }
            ctx.emitRebuildStatement(
                triggerSql,
                opIds, risk = safe, phase = DiffPhase.INDEXES,
            )
        }

        // CLEANUP phase: integrity check + commit + re-enable FKs.
        ctx.emitRebuildStatement("PRAGMA foreign_key_check;", opIds, risk = safe, phase = DiffPhase.CLEANUP)
        ctx.emitRebuildStatement("COMMIT;", opIds, risk = safe, phase = DiffPhase.CLEANUP)
        // Phase H.3b: in STANDALONE mode pin FK back to ON (the safe
        // default for an external runner that doesn't know prior state).
        // In EXECUTE mode emit a hook marker; the d-migrate runner
        // reads `PRAGMA foreign_keys;` before the sequence and restores
        // that value here.
        when (plan.emissionMode) {
            SqliteRebuildEmissionMode.STANDALONE ->
                ctx.emitRebuildStatement("PRAGMA foreign_keys = ON;", opIds, risk = safe, phase = DiffPhase.CLEANUP)
            SqliteRebuildEmissionMode.EXECUTE ->
                ctx.emitRebuildStatement(
                    "-- dmigrate:runner-hook=restore-fk-state",
                    opIds, risk = safe, phase = DiffPhase.CLEANUP,
                )
        }

        for (op in plan.bucketOperations) ctx.markRendered(op)
        ctx.applyBucketRisk(opIds, bucketRisk)
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

    private fun buildInsertSelectSql(
        tempName: String,
        originalTable: String,
        mapping: SqliteColumnMappingModel,
    ): String {
        val entries = mapping.orderedInsertEntries
        val targetCols = entries.joinToString(", ") { sql.quote(it.targetColumn) }
        val selectExprs = entries.joinToString(", ") { it.expressionSql }
        return "INSERT INTO ${sql.quote(tempName)} ($targetCols) " +
            "SELECT $selectExprs FROM ${sql.quote(originalTable)};"
    }
}

