package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.util.sha256Hex

/**
 * F.5 Sub-Slice E.2 (2026-05-19): dialect-neutral planner that maps
 * every `AddConstraint(CHECK)` operation in a [DiffResult] to a
 * [PlannedCheckPreflight] declaration.
 *
 * The planner runs *after* [DiffPlanner] and *before* rendering. The
 * caller (`SchemaMigrateRenderPipeline` in the application layer)
 * passes:
 *
 * - the freshly-planned [DiffResult];
 * - the target [dialect] name (matches
 *   [dev.dmigrate.driver.DatabaseDialect.name] lowercased — `"postgresql"`,
 *   `"mysql"`, `"sqlite"`); the value is informational and surfaced in
 *   the produced declarations;
 * - a function ([identifierQuoter]) that quotes a single SQL
 *   identifier for the target dialect. The planner uses it to build
 *   the deterministic probe SQL hash so two runs against the same
 *   schema state produce the same binding key;
 * - the initial [CheckPreflightStatus]-equivalent [initialStatus] —
 *   one of `NOT_RUN_FILE_TARGET`, `NOT_RUN_POLICY`, etc. The runner
 *   stage replaces this with `PASSED`/`FAILED` once the live probe
 *   has run.
 *
 * Operations that the planner intentionally skips:
 *
 * - `DropConstraint(CHECK)` — dropping a constraint never violates
 *   existing data; no preflight needed.
 * - `AddConstraint(EXCLUDE)` and `DropConstraint(EXCLUDE)` — EXCLUDE
 *   is blocked upstream by per-dialect renderers; the preflight has
 *   nothing to verify.
 * - `AddConstraint(UNIQUE / FOREIGN_KEY)` — those have their own
 *   pre-existing renderer-level checks; out of scope here.
 *
 * Blank expressions also produce no declaration: the renderer
 * already routes those to `DIALECT_UNSUPPORTED_OPERATION` via the
 * builder's `constraintLine` returning `null`, so emitting a probe
 * declaration would just be dead weight in the report.
 */
object CheckPreflightPlanner {

    /**
     * Initial status the planner stamps onto every declaration. The
     * runner stage overwrites this with `PASSED` / `FAILED` once the
     * live probe runs (or with `PROBE_RUNTIME_ERROR` if the probe
     * itself fails). Strings are used here (not the
     * `CheckPreflightStatus` enum) so this module stays free of the
     * `ports-read` dependency — the application layer translates.
     */
    enum class InitialStatus {
        NOT_RUN_FILE_TARGET,
        NOT_RUN_POLICY,
    }

    /**
     * Planner output. Lives in `hexagon:core` (the module producing
     * it). The application layer converts each entry into a
     * `CheckPreflightDeclaration` (ports-read) when wiring the
     * pipeline.
     */
    data class PlannedCheckPreflight(
        val operationId: String,
        val dialect: String,
        val table: String,
        val constraintName: String,
        val expression: String,
        val initialStatus: InitialStatus,
        val probeSql: String,
        val sqlHash: String,
    )

    fun plan(
        diff: DiffResult,
        dialect: String,
        initialStatus: InitialStatus,
        identifierQuoter: (String) -> String,
    ): List<PlannedCheckPreflight> {
        val out = mutableListOf<PlannedCheckPreflight>()
        for (op in diff.operations) {
            if (op !is DiffOperation.AddConstraint) continue
            if (op.constraint.type != ConstraintType.CHECK) continue
            val expression = op.constraint.expression?.takeIf { it.isNotBlank() } ?: continue
            val table = op.objectRef.path.firstOrNull() ?: continue
            val sql = "SELECT count(*) FROM ${identifierQuoter(table)} WHERE NOT (${expression})"
            out += PlannedCheckPreflight(
                operationId = op.id,
                dialect = dialect,
                table = table,
                constraintName = op.constraint.name,
                expression = expression,
                initialStatus = initialStatus,
                probeSql = sql,
                sqlHash = sha256Hex(sql),
            )
        }
        return out
    }
}
