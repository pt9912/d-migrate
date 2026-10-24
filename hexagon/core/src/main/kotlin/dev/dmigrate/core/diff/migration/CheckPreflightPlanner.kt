package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.EnumCheckProjection
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
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
 *   existing data; no preflight needed. NOTE: this also means the
 *   *Down*-direction of a `DropConstraint(CHECK)` (a logical re-add
 *   during rollback) has no preflight either. The contract is
 *   conservative: rolling back a previously-applied CHECK drop
 *   replays the original CREATE-time data shape, which the operator
 *   verified once before applying the Up. If you need explicit
 *   rollback-time verification, run a fresh `schema migrate --execute`
 *   from the rolled-back state.
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

    /**
     * [expressionText] schreibt den neutralen Ausdruck in die Form, die der
     * Dialekt liest — dieselbe, mit der sein Generator den CHECK rendert
     * (MySQL: `"…"` als Backtick-Bezeichner). Die Sonde muss dieselbe
     * Bedingung pruefen, die danach angelegt wird. [PlannedCheckPreflight.expression]
     * bleibt der neutrale Text.
     */
    fun plan(
        diff: DiffResult,
        dialect: String,
        initialStatus: InitialStatus,
        identifierQuoter: (String) -> String,
        expressionText: (String) -> String = { it },
        refTypeVocabulary: Boolean = false,
    ): List<PlannedCheckPreflight> {
        val out = mutableListOf<PlannedCheckPreflight>()
        for (op in diff.operations) {
            val planned = when {
                op is DiffOperation.AddConstraint -> addConstraintProbe(op, dialect, initialStatus, identifierQuoter, expressionText)
                refTypeVocabulary && op is DiffOperation.AlterColumnType ->
                    vocabularyProbe(op, diff, dialect, initialStatus, identifierQuoter)
                else -> null
            }
            if (planned != null) out += planned
        }
        return out
    }

    private fun addConstraintProbe(
        op: DiffOperation.AddConstraint,
        dialect: String,
        initialStatus: InitialStatus,
        identifierQuoter: (String) -> String,
        expressionText: (String) -> String,
    ): PlannedCheckPreflight? {
        if (op.constraint.type != ConstraintType.CHECK) return null
        val expression = op.constraint.expression?.takeIf { it.isNotBlank() } ?: return null
        val table = op.objectRef.path.firstOrNull() ?: return null
        val sql = "SELECT count(*) FROM ${identifierQuoter(table)} WHERE NOT (${expressionText(expression)})"
        return PlannedCheckPreflight(
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

    /**
     * Der Wertevorrat eines `ref_type`-Enums als Sonde: eine Spalte, deren neuer
     * Typ ein ENUM der Custom Types des Soll-Schemas ist, bekommt einen CHECK, den
     * die vorhandenen Zeilen verletzen koennen (Wert entfernt). Ohne die Sonde
     * brach erst der `INSERT ... SELECT` des Neubaus ab.
     *
     * Nur fuer Dialekte, die den Wertevorrat so aufloesen (SQLite); sonst ist
     * `AlterColumnType` eine Typaenderung ohne CHECK.
     */
    private fun vocabularyProbe(
        op: DiffOperation.AlterColumnType,
        diff: DiffResult,
        dialect: String,
        initialStatus: InitialStatus,
        identifierQuoter: (String) -> String,
    ): PlannedCheckPreflight? {
        val type = op.after as? NeutralType.Enum ?: return null
        val definition = type.refType?.let { diff.desiredSchema?.customTypes?.get(it) }
            ?.takeIf { it.kind == CustomTypeKind.ENUM } ?: return null
        val values = definition.values?.takeIf { it.isNotEmpty() } ?: return null
        val table = op.objectRef.path.getOrNull(0) ?: return null
        val column = op.objectRef.path.getOrNull(1) ?: return null
        val expression = EnumCheckProjection.canonicalText(identifierQuoter(column), values)
        val sql = "SELECT count(*) FROM ${identifierQuoter(table)} WHERE NOT ($expression)"
        return PlannedCheckPreflight(
            operationId = op.id,
            dialect = dialect,
            table = table,
            constraintName = column,
            expression = expression,
            initialStatus = initialStatus,
            probeSql = sql,
            sqlHash = sha256Hex(sql),
        )
    }
}
