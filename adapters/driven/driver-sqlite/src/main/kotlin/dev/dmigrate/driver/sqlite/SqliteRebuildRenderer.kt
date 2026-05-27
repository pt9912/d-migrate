package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.ExecutionMode
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.SqliteCastPreflightStatus
import dev.dmigrate.driver.sqliteContext
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
        if (plan.hasMaterializedDependentViews()) {
            emitMaterializedViewBlocker(plan, ctx)
            return
        }
        if (plan.mapping.isBlocked) {
            emitBlockerDiagnostics(plan, ctx)
            return
        }
        if (emitCastPreflightBlockersIfAny(plan, ctx)) return
        emitRebuildSequence(plan, ctx)
        emitPreflightInfoDiagnostics(plan, ctx)
        emitCastPreflightInfoDiagnostics(plan, ctx)
    }

    private fun emitCastPreflightBlockersIfAny(plan: SqliteRebuildPlan, ctx: SqliteDiffRenderContext): Boolean {
        val required = requiredCastPreflights(plan, ctx.direction)
        if (required.isEmpty()) return false
        val byKey = (ctx.options.sqliteContext?.castPreflights ?: emptyList()).associateBy { it.bindingKey }
        var blocked = false
        for (binding in required) {
            val declaration = byKey[binding.bindingKey]
            when {
                ctx.options.executionMode == ExecutionMode.EXECUTE && declaration == null -> {
                    ctx.recordSqliteCastPreflight(
                        binding.toDeclaration(
                            status = SqliteCastPreflightStatus.NOT_RUN_POLICY,
                            problem = "Execute requires a fresh SQLite cast preflight declaration.",
                        ),
                    )
                    ctx.addDiagnostic(
                        DiffDiagnostic(
                            code = "SQLITE_CAST_PREFLIGHT_MISSING",
                            message = "SQLite RebuildTable cast preflight is missing for " +
                                "`${binding.table}.${binding.column}` (${binding.sourceTypeText} -> " +
                                "${binding.targetTypeText}, sqlHash=${binding.sqlHash}). Execute is blocked " +
                                "before rendering the CAST copy step.",
                            severity = DiffDiagnostic.Severity.BLOCKER,
                            operationId = binding.operationId,
                        ),
                    )
                    blocked = true
                }
                declaration?.status == SqliteCastPreflightStatus.FAILED -> {
                    ctx.recordSqliteCastPreflight(declaration)
                    val sample = if (declaration.sampleRowIds.isEmpty()) {
                        ""
                    } else {
                        "; sample rowids=" + declaration.sampleRowIds.joinToString(",")
                    }
                    ctx.addDiagnostic(
                        DiffDiagnostic(
                            code = "SQLITE_CAST_PREFLIGHT_FAILED",
                            message = "SQLite RebuildTable cast preflight failed for " +
                                "`${binding.table}.${binding.column}` (${binding.sourceTypeText} -> " +
                                "${binding.targetTypeText}, sqlHash=${binding.sqlHash}): " +
                                "${declaration.failingRows ?: 0} row(s) are not safely convertible$sample.",
                            severity = DiffDiagnostic.Severity.BLOCKER,
                            operationId = binding.operationId,
                        ),
                    )
                    blocked = true
                }
                declaration != null && declaration.status != SqliteCastPreflightStatus.PASSED -> {
                    ctx.recordSqliteCastPreflight(declaration)
                    ctx.addDiagnostic(
                        DiffDiagnostic(
                            code = "SQLITE_CAST_PREFLIGHT_${declaration.status.name}",
                            message = "SQLite RebuildTable cast preflight for " +
                                "`${binding.table}.${binding.column}` has status ${declaration.status} " +
                                "(sqlHash=${binding.sqlHash}); execute requires PASSED.",
                            severity = if (ctx.options.executionMode == ExecutionMode.EXECUTE) {
                                DiffDiagnostic.Severity.BLOCKER
                            } else {
                                DiffDiagnostic.Severity.INFO
                            },
                            operationId = binding.operationId,
                        ),
                    )
                    if (ctx.options.executionMode == ExecutionMode.EXECUTE) blocked = true
                }
            }
        }
        if (blocked) {
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, required.map { it.operationId }.toSet())
            for (op in plan.bucketOperations) ctx.markRendered(op)
        }
        return blocked
    }

    /**
     * Phase H.4: surface non-PASS preflight entries as
     * [DiffDiagnostic]s on the render context so the migrate-report
     * (and downstream MCP/JSON consumers) can see the per-kind
     * outcome without inspecting the plan directly.
     *
     * Emits only the INFO entries — the FAIL pathway is covered by
     * the existing parallel diagnostics (NOT_NULL_BACKFILL_REQUIRED,
     * SQLITE_CAST_NOT_WHITELISTED) that the blocker path already
     * raised. PASS entries are silent (no signal needed).
     */
    private fun emitPreflightInfoDiagnostics(plan: SqliteRebuildPlan, ctx: SqliteDiffRenderContext) {
        for (check in plan.preflight) {
            if (check.outcome != SqliteRebuildPreflightOutcome.INFO) continue
            ctx.addDiagnostic(
                dev.dmigrate.core.diff.migration.DiffDiagnostic(
                    code = "SQLITE_REBUILD_PREFLIGHT_${check.kind.name}",
                    message = check.message + (check.target?.let { " (target: $it)" } ?: ""),
                    severity = dev.dmigrate.core.diff.migration.DiffDiagnostic.Severity.INFO,
                ),
            )
        }
    }

    private fun emitCastPreflightInfoDiagnostics(plan: SqliteRebuildPlan, ctx: SqliteDiffRenderContext) {
        val required = requiredCastPreflights(plan, ctx.direction)
        if (required.isEmpty()) return
        val byKey = (ctx.options.sqliteContext?.castPreflights ?: emptyList()).associateBy { it.bindingKey }
        for (binding in required) {
            val declaration = byKey[binding.bindingKey]
            if (declaration == null) {
                ctx.recordSqliteCastPreflight(binding.toDeclaration(SqliteCastPreflightStatus.NOT_RUN_FILE_TARGET))
                ctx.addDiagnostic(
                    DiffDiagnostic(
                        code = "SQLITE_CAST_PREFLIGHT_NOT_RUN_FILE_TARGET",
                        message = "SQLite RebuildTable cast preflight was not run for " +
                            "`${binding.table}.${binding.column}` (${binding.sourceTypeText} -> " +
                            "${binding.targetTypeText}, status=NOT_RUN_FILE_TARGET, sqlHash=${binding.sqlHash}).",
                        severity = DiffDiagnostic.Severity.INFO,
                        operationId = binding.operationId,
                    ),
                )
                continue
            }
            ctx.recordSqliteCastPreflight(declaration)
            ctx.addDiagnostic(
                DiffDiagnostic(
                    code = "SQLITE_CAST_PREFLIGHT_${declaration.status.name}",
                    message = "SQLite RebuildTable cast preflight ${declaration.status} for " +
                        "`${binding.table}.${binding.column}` (${binding.sourceTypeText} -> " +
                        "${binding.targetTypeText}, totalRows=${declaration.totalRows ?: "unknown"}, " +
                        "failingRows=${declaration.failingRows ?: "unknown"}, sqlHash=${binding.sqlHash}).",
                    severity = DiffDiagnostic.Severity.INFO,
                    operationId = binding.operationId,
                ),
            )
        }
    }

    private fun requiredCastPreflights(
        plan: SqliteRebuildPlan,
        direction: SqliteRenderDirection,
    ): List<SqliteCastPreflightBinding> {
        val out = mutableListOf<SqliteCastPreflightBinding>()
        for (op in plan.bucketOperations) {
            if (op !is DiffOperation.AlterColumnType) continue
            val source = if (direction == SqliteRenderDirection.UP) op.before else op.after
            val target = if (direction == SqliteRenderDirection.UP) op.after else op.before
            if (!SqliteCastMatrix.isWhitelisted(source, target)) continue
            out += SqliteCastPreflightSql.bindingFor(
                operationId = op.id,
                table = plan.originalTableName,
                column = op.objectRef.path[1],
                sourceType = source,
                targetType = target,
                sql = sql,
            )
        }
        return out.sortedWith(compareBy({ it.table }, { it.column }, { it.operationId }))
    }

    private fun SqliteCastPreflightBinding.toDeclaration(
        status: SqliteCastPreflightStatus,
        problem: String? = null,
    ): SqliteCastPreflightDeclaration =
        SqliteCastPreflightDeclaration(
            operationId = operationId,
            dialect = dialect,
            table = table,
            column = column,
            sourceType = sourceTypeText,
            targetType = targetTypeText,
            status = status,
            sqlHash = sqlHash,
            problem = problem,
        )

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

    private fun SqliteRebuildPlan.hasMaterializedDependentViews(): Boolean =
        (dependentViewsToDrop + dependentViewsToRecreate).any { it.definition.materialized }

    private fun emitMaterializedViewBlocker(plan: SqliteRebuildPlan, ctx: SqliteDiffRenderContext) {
        val names = (plan.dependentViewsToDrop + plan.dependentViewsToRecreate)
            .filter { it.definition.materialized }
            .map { it.name }
            .distinct()
            .sorted()
        val message = "RebuildTable for `${plan.originalTableName}` would drop/recreate " +
            "materialized view(s) ${names.joinToString(", ")} for dialect sqlite " +
            "(materialized=true). Diff-based materialized-view migrations are blocked " +
            "until a dedicated emulation/refresh contract exists."
        for (op in plan.bucketOperations) {
            ctx.skip(op, message, code = "MATERIALIZED_VIEW_DIFF_UNSUPPORTED")
        }
        ctx.addBlocker(
            MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            operationIds = plan.sourceOperationIds,
        )
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
                hints = SqliteDiffRenderContext.SQLITE_REBUILD_OUTSIDE_TX_HINTS,
            )
        }
        ctx.emitRebuildStatement(
            "PRAGMA foreign_keys = OFF;",
            opIds, risk = safe, phase = DiffPhase.PREPARE,
            hints = SqliteDiffRenderContext.SQLITE_REBUILD_OUTSIDE_TX_HINTS,
        )
        ctx.emitRebuildStatement(
            "BEGIN IMMEDIATE;",
            opIds, risk = safe, phase = DiffPhase.PREPARE,
            hints = SqliteDiffRenderContext.SQLITE_TX_MARKER_HINTS,
        )

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

        emitCleanupPhase(plan, ctx, opIds, safe)

        for (op in plan.bucketOperations) ctx.markRendered(op)
        ctx.applyBucketRisk(opIds, bucketRisk)
    }

    /**
     * CLEANUP phase: integrity check + commit + re-enable FKs.
     *
     * Phase H.4 `FOREIGN_KEYS_CHECKABLE` runner-vertrag: in
     * STANDALONE mode emit `PRAGMA foreign_key_check;` directly
     * (external runners that don't read the result-set treat it as
     * informational); in EXECUTE mode emit a hook marker so the
     * d-migrate runner reads the cursor and throws on violation.
     *
     * Phase H.3b: STANDALONE pins FK back to ON (safe default for
     * external runners); EXECUTE emits a hook marker so the
     * d-migrate runner restores the prior value it captured in
     * PREPARE.
     */
    private fun emitCleanupPhase(
        plan: SqliteRebuildPlan,
        ctx: SqliteDiffRenderContext,
        opIds: Set<String>,
        safe: OperationRisk,
    ) {
        when (plan.emissionMode) {
            SqliteRebuildEmissionMode.STANDALONE ->
                ctx.emitRebuildStatement(
                    "PRAGMA foreign_key_check;",
                    opIds, risk = safe, phase = DiffPhase.CLEANUP,
                    hints = SqliteDiffRenderContext.SQLITE_TX_MARKER_HINTS,
                )
            SqliteRebuildEmissionMode.EXECUTE ->
                ctx.emitRebuildStatement(
                    "-- dmigrate:runner-hook=assert-foreign-keys-clean",
                    opIds, risk = safe, phase = DiffPhase.CLEANUP,
                    hints = SqliteDiffRenderContext.SQLITE_TX_MARKER_HINTS,
                )
        }
        ctx.emitRebuildStatement(
            "COMMIT;",
            opIds, risk = safe, phase = DiffPhase.CLEANUP,
            hints = SqliteDiffRenderContext.SQLITE_TX_MARKER_HINTS,
        )
        when (plan.emissionMode) {
            SqliteRebuildEmissionMode.STANDALONE ->
                ctx.emitRebuildStatement(
                    "PRAGMA foreign_keys = ON;",
                    opIds, risk = safe, phase = DiffPhase.CLEANUP,
                    hints = SqliteDiffRenderContext.SQLITE_REBUILD_OUTSIDE_TX_HINTS,
                )
            SqliteRebuildEmissionMode.EXECUTE ->
                ctx.emitRebuildStatement(
                    "-- dmigrate:runner-hook=restore-fk-state",
                    opIds, risk = safe, phase = DiffPhase.CLEANUP,
                    hints = SqliteDiffRenderContext.SQLITE_REBUILD_OUTSIDE_TX_HINTS,
                )
        }
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
