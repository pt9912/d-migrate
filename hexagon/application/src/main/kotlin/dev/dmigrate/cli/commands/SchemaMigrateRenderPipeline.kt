package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.EffectiveRoutineCapability
import dev.dmigrate.driver.ExecutionMode
import dev.dmigrate.driver.ExtensionInstallPolicy
import dev.dmigrate.driver.MysqlServerVersion
import dev.dmigrate.driver.RoutineCapabilityDefaults
import dev.dmigrate.driver.SpatialProfilePolicy
import dev.dmigrate.driver.SqliteCatalogProbeMode
import dev.dmigrate.driver.SqliteLiveCatalog
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import java.nio.file.Path

/**
 * Result of [SchemaMigrateRenderPipeline.run]. The pipeline emits both
 * the rendered Up stream and (optionally) Down stream plus the
 * `combined` merge consumed by report + rollback paths. Carries the
 * probe-derived `catalogProbeMode` so the runner can surface it in the
 * report summary.
 */
internal data class SchemaMigrateRenderResult(
    val effectiveUp: MigrationDdlResult,
    val renderedDown: MigrationDdlResult?,
    val combined: MigrationDdlResult,
    val executableCombined: MigrationDdlResult,
    val catalogProbeMode: SqliteCatalogProbeMode,
)

/**
 * Pipeline stage that turns a [DiffResult] plus its overlay preflight
 * into a rendered SQL stream (Up, optionally Down) plus the
 * `combined`/`executableCombined` views consumed by the executor and
 * report builder.
 *
 * Responsibilities pulled out of [SchemaMigrateRunner] to keep that
 * class under Detekt's `LargeClass` budget:
 *
 * - SQLite probe (`sqlite_master`) + cast-preflight live-data probe.
 * - `DdlGenerationOptions` assembly.
 * - Renderer dispatch with overlay-blocker / probe-failure /
 *   cast-preflight-failure short-circuits.
 * - Destructive guard, Down render, Up+Down merge.
 * - Transaction-scope guard.
 */
internal class SchemaMigrateRenderPipeline(
    private val sqliteLiveCatalogProbe: ((CompareOperand.Database, Path?) -> SqliteLiveCatalog)?,
    private val sqliteCastPreflightPlanner: SqliteCastPreflightPlannerFn?,
    private val sqliteCastPreflightProbe: SqliteCastPreflightProbeFn?,
) {

    fun run(
        request: SchemaMigrateRequest,
        targetOp: CompareOperand,
        dialect: DatabaseDialect,
        renderer: DiffDdlGenerator,
        plan: DiffResult,
        overlayPreflight: MigrationOverlayPreflightResult,
        cancellationToken: CancellationToken,
        mysqlServerVersion: MysqlServerVersion? = null,
        routineCapabilityResolver: ((EffectiveRoutineCapability.Valid) -> EffectiveRoutineCapability)? = null,
    ): SchemaMigrateRenderResult {
        val probeOutcome = runProbe(request, targetOp, dialect, overlayPreflight)
        val castPreflightPlan = runCastPreflightPlan(request, targetOp, dialect, plan, overlayPreflight)
        val castPreflightOutcome = runCastPreflight(
            request,
            targetOp,
            dialect,
            plan,
            castPreflightPlan,
            overlayPreflight,
        )
        val renderOptions = buildRenderOptions(
            request, dialect, probeOutcome, castPreflightOutcome, castPreflightPlan, mysqlServerVersion,
            routineCapabilityResolver,
        )
        val renderedUp = renderUp(plan, overlayPreflight, renderer, renderOptions, probeOutcome, castPreflightOutcome)
        val effectiveUp = MigrateDestructiveGuard.apply(renderedUp, request.allowDestructive)

        val renderedDown = if (request.generateRollback && !overlayPreflight.hasBlockers) {
            cancellationToken.throwIfCancellationRequested()
            renderer.generateDown(
                plan,
                renderOptions.copy(executionMode = ExecutionMode.STANDALONE),
            )
        } else {
            null
        }
        val combined = if (renderedDown == null) effectiveUp else mergeDownIntoUp(effectiveUp, renderedDown)
        val executableCombined = applyTransactionScopeGuard(request, combined)
        return SchemaMigrateRenderResult(
            effectiveUp = effectiveUp,
            renderedDown = renderedDown,
            combined = combined,
            executableCombined = executableCombined,
            catalogProbeMode = renderOptions.catalogProbeMode,
        )
    }

    private fun runProbe(
        request: SchemaMigrateRequest,
        targetOp: CompareOperand,
        dialect: DatabaseDialect,
        overlayPreflight: MigrationOverlayPreflightResult,
    ): SqliteProbeStage.Outcome = if (overlayPreflight.hasBlockers) {
        SqliteProbeStage.Outcome.NotRun
    } else {
        SqliteProbeStage.run(sqliteLiveCatalogProbe, request, targetOp, dialect)
    }

    private fun runCastPreflightPlan(
        request: SchemaMigrateRequest,
        targetOp: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
        overlayPreflight: MigrationOverlayPreflightResult,
    ): MigrationPreflightPlan = if (overlayPreflight.hasBlockers) {
        MigrationPreflightPlan.EMPTY
    } else {
        MigrationPreflightPlanner.plan(
            sqliteCastPreflightPlanner,
            request,
            targetOp,
            dialect,
            plan,
        )
    }

    private fun runCastPreflight(
        request: SchemaMigrateRequest,
        targetOp: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
        castPreflightPlan: MigrationPreflightPlan,
        overlayPreflight: MigrationOverlayPreflightResult,
    ): SqliteCastPreflightStage.Outcome = if (overlayPreflight.hasBlockers) {
        SqliteCastPreflightStage.Outcome.NotRun
    } else {
        SqliteCastPreflightStage.run(
            sqliteCastPreflightProbe,
            sqliteCastPreflightPlanner,
            request,
            targetOp,
            dialect,
            plan,
            castPreflightPlan,
        )
    }

    private fun buildRenderOptions(
        request: SchemaMigrateRequest,
        dialect: DatabaseDialect,
        probeOutcome: SqliteProbeStage.Outcome,
        castPreflightOutcome: SqliteCastPreflightStage.Outcome,
        castPreflightPlan: MigrationPreflightPlan,
        mysqlServerVersion: MysqlServerVersion?,
        routineCapabilityResolver: ((EffectiveRoutineCapability.Valid) -> EffectiveRoutineCapability)?,
    ): DdlGenerationOptions {
        val defaultsForKindAndVersion = if (dialect == DatabaseDialect.MYSQL) {
            RoutineCapabilityDefaults.forMysqlServerVersion(mysqlServerVersion)
        } else {
            RoutineCapabilityDefaults.forDialect(dialect)
        }
        val routineCapability: EffectiveRoutineCapability =
            routineCapabilityResolver?.invoke(defaultsForKindAndVersion) ?: defaultsForKindAndVersion
        return DdlGenerationOptions(
            spatialProfile = SpatialProfilePolicy.defaultFor(dialect),
            executionMode = if (request.execute) ExecutionMode.EXECUTE else ExecutionMode.STANDALONE,
            liveSqliteCatalog = (probeOutcome as? SqliteProbeStage.Outcome.Succeeded)?.catalog,
            sqliteCastPreflights = when (castPreflightOutcome) {
                is SqliteCastPreflightStage.Outcome.Succeeded -> castPreflightOutcome.declarations
                else -> castPreflightPlan.sqliteCastPreflights
            },
            catalogProbeMode = if (probeOutcome is SqliteProbeStage.Outcome.Succeeded) {
                SqliteCatalogProbeMode.LIVE_SQLITE_MASTER
            } else {
                SqliteCatalogProbeMode.SCHEMA_ONLY
            },
            extensionInstallPolicy = if (request.allowExtensionInstall) {
                ExtensionInstallPolicy.ALLOW_CREATE_IF_MISSING
            } else {
                ExtensionInstallPolicy.NEVER
            },
            routineCapability = routineCapability,
            mysqlServerVersion = mysqlServerVersion,
        )
    }

    private fun renderUp(
        plan: DiffResult,
        overlayPreflight: MigrationOverlayPreflightResult,
        renderer: DiffDdlGenerator,
        renderOptions: DdlGenerationOptions,
        probeOutcome: SqliteProbeStage.Outcome,
        castPreflightOutcome: SqliteCastPreflightStage.Outcome,
    ): MigrationDdlResult = when {
        overlayPreflight.hasBlockers ->
            MigrationOverlayPreflight.buildFailureResult(plan, overlayPreflight)
        probeOutcome is SqliteProbeStage.Outcome.Failed ->
            SqliteProbeStage.buildFailureResult(probeOutcome.message)
        castPreflightOutcome is SqliteCastPreflightStage.Outcome.Failed ->
            SqliteCastPreflightStage.buildFailureResult(
                castPreflightOutcome.message,
                castPreflightOutcome.declarations,
            )
        else -> {
            val rendered = renderer.generateUp(plan, renderOptions)
            if (probeOutcome is SqliteProbeStage.Outcome.NotRun) {
                rendered.copy(diagnostics = rendered.diagnostics + SqliteProbeStage.buildNotRunDiagnostic())
            } else {
                rendered
            }
        }
    }

    /**
     * Merge Down-rendering blockers into the Up result so callers see a
     * unified [MigrationDdlResult]. Up's statements / rendered ops are
     * preserved (still useful for the report) but Down's blockers
     * propagate so the runner exits 8.
     */
    private fun mergeDownIntoUp(up: MigrationDdlResult, down: MigrationDdlResult): MigrationDdlResult {
        if (down.blockers.isEmpty()) {
            return up.copy(sqliteCastPreflights = up.sqliteCastPreflights + down.sqliteCastPreflights)
        }
        val merged = up.blockers + down.blockers
        val primary = up.primaryBlockedReason ?: down.primaryBlockedReason
        return up.copy(
            blockers = merged,
            primaryBlockedReason = primary,
            diagnostics = up.diagnostics + down.diagnostics,
            sqliteCastPreflights = up.sqliteCastPreflights + down.sqliteCastPreflights,
        )
    }

    private fun applyTransactionScopeGuard(
        request: SchemaMigrateRequest,
        rendered: MigrationDdlResult,
    ): MigrationDdlResult {
        if (!request.execute || rendered.isBlocked) return rendered
        val reason = MigrationStreamClassifier.unsupportedTransactionScopeReason(rendered.statements) ?: return rendered
        val opIds = rendered.statements.flatMap { it.operationIds }.toSortedSet()
        val diagnostic = DiffDiagnostic(
            code = "TRANSACTION_SCOPE_UNSUPPORTED",
            message = reason,
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = opIds.singleOrNull(),
        )
        val blocker = MigrationBlocker(
            reason = MigrationBlockedReason.TRANSACTION_SCOPE_UNSUPPORTED,
            operationIds = opIds,
            diagnostics = listOf(diagnostic),
        )
        return rendered.copy(
            blockers = rendered.blockers + blocker,
            primaryBlockedReason = rendered.primaryBlockedReason
                ?: MigrationBlockedReason.TRANSACTION_SCOPE_UNSUPPORTED,
            diagnostics = rendered.diagnostics + diagnostic,
        )
    }
}
