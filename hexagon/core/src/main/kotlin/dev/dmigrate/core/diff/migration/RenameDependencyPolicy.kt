package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SchemaDefinition

/**
 * F.4 dependency-projection T3: per-dialect policy that classifies
 * the dependencies of a rename candidate into the buckets carried by
 * [RenameProjection].
 *
 * Policies are stateless `object`s — instance-per-dialect is sufficient
 * since the dialect identity is the only configuration knob and is
 * carried by [dialect]. The factory in the companion picks the right
 * policy from [RenameProjectionCapabilities.dialect] so the projector
 * doesn't need to switch on dialect itself.
 *
 * Acceptance matrix per Plan-2 §F.4 §3.3:
 *
 * | Dependency                | PostgreSQL              | MySQL                          | SQLite                              |
 * |---------------------------|-------------------------|--------------------------------|-------------------------------------|
 * | FK + index across rename  | AUTOMATIC_BY_ENGINE     | AUTOMATIC_BY_ENGINE if LIVE    | AUTOMATIC_BY_ENGINE if pinned       |
 * | View / Trigger / Routine  | AUTOMATIC iff provenance| EXPLICIT_REPROJECTION (T5)     | AUTOMATIC if pinned, else BLOCK     |
 * | Default-Expression fn-call| NO_PROJECTION_AVAILABLE | NO_PROJECTION_AVAILABLE        | NO_PROJECTION_AVAILABLE             |
 *
 * T3 only implements the **conservative-block** path: every policy
 * blocks on `DefaultValue.FunctionCall` defaults in the rename
 * environment. T4 adds the synthetic delta operations that complete
 * the AUTOMATIC case for mixed renames; T5 introduces the
 * EXPLICIT_REPROJECTION bucket for view/trigger drop+create.
 *
 * The SQLite policy additionally requires pinned version + PRAGMA
 * capabilities (`sqliteVersion >= 3.26` AND
 * `sqliteLegacyAlterTable == false`) before classifying any FK /
 * index / view dependency as `AUTOMATIC`; unknown capabilities under
 * a `FILE_ONLY` source surface a `RENAME_DEPENDENCY_UNPROJECTABLE`
 * blocker.
 */
internal interface RenameDependencyPolicy {

    val dialect: RenameProjectionDialect

    fun classifyTableRename(
        candidate: RenameTableCandidate,
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection

    fun classifyColumnRename(
        candidate: RenameColumnCandidate,
        table: TableDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection

    companion object {
        fun forDialect(dialect: RenameProjectionDialect): RenameDependencyPolicy = when (dialect) {
            RenameProjectionDialect.POSTGRESQL -> PostgresRenameDependencyPolicy
            RenameProjectionDialect.MYSQL -> MysqlRenameDependencyPolicy
            RenameProjectionDialect.SQLITE -> SqliteRenameDependencyPolicy
        }
    }
}

/**
 * Shared rename-dependency probes: pure functions over the
 * schema model that the per-dialect policies compose. Kept dialect-
 * agnostic so the dialect implementations only carry the matrix
 * decisions and not the schema-walking logic.
 */
internal object RenameDependencyProbes {

    /**
     * Returns blockers for `DefaultValue.FunctionCall` defaults in
     * [table] whose function-name string contains the renamed
     * column's old name as a substring (case-insensitive). The model
     * treats the function body as opaque (`DefaultValue.FunctionCall`
     * only carries a single string); a substring match is the
     * narrowest principled signal d-migrate can derive without a
     * default-expression parser.
     *
     * The probe is intentionally **column-scope only**: a table
     * rename leaves the column-default expressions untouched (engines
     * update the table identity in the catalog without rewriting the
     * defaults), so running this probe on a table rename would only
     * produce false positives.
     */
    fun functionCallReferencingOldColumnName(
        candidateId: String,
        tableName: String,
        oldColumnName: String,
        table: dev.dmigrate.core.model.TableDefinition?,
    ): List<RenameProjectionBlocker> {
        if (table == null) return emptyList()
        val out = mutableListOf<RenameProjectionBlocker>()
        for ((columnName, column) in table.columns) {
            val default = column.default
            if (default is DefaultValue.FunctionCall &&
                default.name.contains(oldColumnName, ignoreCase = true)
            ) {
                out += RenameProjectionBlocker(
                    code = RENAME_DEPENDENCY_UNPROJECTABLE,
                    candidateId = candidateId,
                    path = listOf(tableName, columnName, "default"),
                    message = "Column '$tableName.$columnName' carries " +
                        "`DefaultValue.FunctionCall(\"${default.name}\")` whose function-name string " +
                        "contains the renamed column's old name '$oldColumnName'. The neutral model " +
                        "treats the function body as opaque — d-migrate cannot rewrite the call to " +
                        "the new name, and the call as stored references a column that will not exist " +
                        "after the rename. Update the default expression to use the new column name, " +
                        "or remove the rename mapping until the slice that introduces explicit " +
                        "default-expression dependencies (Plan-2 §F.4 follow-up).",
                )
            }
        }
        return out
    }
}

/**
 * PostgreSQL is the most permissive dialect: it tracks dependencies
 * via the catalog (OID-based), so FK / Index / PK reprojection after
 * a rename runs natively.
 *
 * **T5 prerequisite:** Views and trigger bodies are textual and
 * remain opaque in the neutral model. PostgreSQL view-deps may only
 * be classified as `AUTOMATIC` once
 * [dev.dmigrate.core.model.ViewDefinition.dependencies] is populated
 * by a trustworthy provenance (catalog-derived). T3 does not
 * enumerate view deps at all, so the gate is not yet load-bearing —
 * the T5 explicit-reprojection slice MUST check the provenance
 * before flipping any view dependency from `EXPLICIT_REPROJECTION` to
 * `AUTOMATIC_BY_ENGINE`.
 *
 * **T3 status:** table renames produce an empty projection (the
 * engine handles the catalog identity natively); column renames run
 * the substring-match `FunctionCall` blocker on defaults that still
 * reference the renamed column's old name.
 */
internal object PostgresRenameDependencyPolicy : RenameDependencyPolicy {
    override val dialect: RenameProjectionDialect = RenameProjectionDialect.POSTGRESQL

    override fun classifyTableRename(
        candidate: RenameTableCandidate,
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection {
        // T3: PostgreSQL's catalog tracks dependencies via OID, so FK /
        // index / PK reprojection runs natively after `ALTER TABLE …
        // RENAME TO`. Column-default expressions don't reference table
        // names, so no FunctionCall probe is needed for table renames.
        // T4/T5 will populate the AUTOMATIC bucket with the actual FK /
        // view-dep refs once the schema model carries them.
        return RenameProjection.EMPTY
    }

    override fun classifyColumnRename(
        candidate: RenameColumnCandidate,
        table: TableDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection {
        val blockers = RenameDependencyProbes.functionCallReferencingOldColumnName(
            candidateId = candidate.id,
            tableName = candidate.tableName,
            oldColumnName = candidate.fromColumn,
            table = desired.tables[candidate.tableName],
        )
        return RenameProjection(blockers = blockers)
    }
}

/**
 * MySQL plans to classify FK + index reprojection as
 * `AUTOMATIC_BY_ENGINE` only when the runner probed the live target
 * before `plan(...)` ([RenameCapabilitySource.LIVE_TARGET]); file-
 * to-file and DB-target runs without an explicit probe will block
 * when an FK dependency would otherwise need to track a
 * constraint-name conflict.
 *
 * **T3 status:** the policy mirrors the PostgreSQL behaviour — table
 * rename is unconditionally automatic, column rename runs the
 * substring-match `FunctionCall` blocker. The LIVE_TARGET-vs-
 * FILE_ONLY gate is **not yet implemented** because T3 does not
 * enumerate FK references from the schema model. Both lands together
 * once T4 introduces the dependency enumeration step.
 */
internal object MysqlRenameDependencyPolicy : RenameDependencyPolicy {
    override val dialect: RenameProjectionDialect = RenameProjectionDialect.MYSQL

    override fun classifyTableRename(
        candidate: RenameTableCandidate,
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection {
        // T3: same as PostgreSQL — column-default expressions don't
        // reference table names, so no blocker fires for table
        // renames. The LIVE_TARGET vs FILE_ONLY gate the matrix
        // describes for FK constraint-name conflicts lands once the
        // policy enumerates FK refs from other tables (T4/T5).
        return RenameProjection.EMPTY
    }

    override fun classifyColumnRename(
        candidate: RenameColumnCandidate,
        table: TableDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection {
        val blockers = RenameDependencyProbes.functionCallReferencingOldColumnName(
            candidateId = candidate.id,
            tableName = candidate.tableName,
            oldColumnName = candidate.fromColumn,
            table = desired.tables[candidate.tableName],
        )
        return RenameProjection(blockers = blockers)
    }
}

/**
 * SQLite column-rename propagation through views and triggers is
 * gated by two PRAGMA-/version-derived capabilities:
 *
 * - `sqliteVersion >= 3.26.0` — earlier versions did not propagate
 *   `RENAME COLUMN` through dependent views or triggers.
 * - `sqliteLegacyAlterTable == false` — when the legacy PRAGMA is
 *   on, the engine omits the same propagation regardless of version.
 *
 * Without both signals, the policy refuses to classify a column
 * rename as automatic: T3 does not yet enumerate view/trigger
 * references, so the safest conservative path is to fall back to
 * drop+add whenever propagation cannot be confirmed.
 *
 * Table renames (`ALTER TABLE … RENAME TO`) are not affected by the
 * version gate — SQLite has supported them since well before 3.x and
 * the rename only touches the catalog identity. T3 lets table
 * renames through with an empty projection; T4/T5 will enumerate FK
 * targets so the LIVE-TARGET-or-test-pinned gate can be re-applied
 * specifically to dependency-bearing renames.
 *
 * Column-default `FunctionCall` expressions that reference the
 * renamed column name (substring match) fire the
 * dialect-agnostic [RenameDependencyProbes] blocker — the engine
 * never rewrites opaque expression bodies regardless of version.
 */
internal object SqliteRenameDependencyPolicy : RenameDependencyPolicy {

    private val MIN_VERSION = ParsedRenameVersion(major = 3, minor = 26, patch = 0)

    override val dialect: RenameProjectionDialect = RenameProjectionDialect.SQLITE

    override fun classifyTableRename(
        candidate: RenameTableCandidate,
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection {
        // Table rename only touches the catalog identity; no version
        // gate, no FunctionCall scope. T4/T5 will re-introduce a gate
        // specifically for FK / view / trigger reprojection.
        return RenameProjection.EMPTY
    }

    override fun classifyColumnRename(
        candidate: RenameColumnCandidate,
        table: TableDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection {
        val blockers = mutableListOf<RenameProjectionBlocker>()
        if (!engineCapabilitiesPinned(capabilities)) {
            blockers += RenameProjectionBlocker(
                code = RENAME_DEPENDENCY_UNPROJECTABLE,
                candidateId = candidate.id,
                path = listOf(candidate.tableName, candidate.toColumn),
                message = "SQLite column-rename propagation through views and triggers requires " +
                    "pinned engine capabilities (sqliteVersion >= 3.26.0 AND " +
                    "sqliteLegacyAlterTable == false, source = LIVE_TARGET or TEST_PINNED). " +
                    "Current capability bundle: source=${capabilities.source}, " +
                    "sqliteVersion=${capabilities.sqliteVersion ?: "<unknown>"}, " +
                    "sqliteLegacyAlterTable=${capabilities.sqliteLegacyAlterTable ?: "<unknown>"}. " +
                    "Fall back to drop+add, pin the capabilities before plan(), or wait for T4 to " +
                    "narrow the gate to dependency-bearing renames only.",
            )
        }
        blockers += RenameDependencyProbes.functionCallReferencingOldColumnName(
            candidateId = candidate.id,
            tableName = candidate.tableName,
            oldColumnName = candidate.fromColumn,
            table = desired.tables[candidate.tableName],
        )
        return RenameProjection(blockers = blockers)
    }

    private fun engineCapabilitiesPinned(capabilities: RenameProjectionCapabilities): Boolean {
        if (capabilities.source == RenameCapabilitySource.FILE_ONLY) return false
        val parsed = RenameProjectionVersionParser.parse(capabilities.sqliteVersion) ?: return false
        if (parsed < MIN_VERSION) return false
        return capabilities.sqliteLegacyAlterTable == false
    }
}
