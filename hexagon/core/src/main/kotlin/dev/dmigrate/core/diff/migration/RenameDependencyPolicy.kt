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
     * Returns blockers for every `DefaultValue.FunctionCall` default
     * present in [table]'s rename environment. The model treats the
     * function body as opaque, so we conservatively assume the
     * function reads or writes the renamed column.
     */
    fun functionCallDefaultBlockers(
        candidateId: String,
        tableName: String,
        table: dev.dmigrate.core.model.TableDefinition?,
    ): List<RenameProjectionBlocker> {
        if (table == null) return emptyList()
        val out = mutableListOf<RenameProjectionBlocker>()
        for ((columnName, column) in table.columns) {
            val default = column.default
            if (default is DefaultValue.FunctionCall) {
                out += RenameProjectionBlocker(
                    code = RENAME_DEPENDENCY_UNPROJECTABLE,
                    candidateId = candidateId,
                    path = listOf(tableName, columnName, "default"),
                    message = "Column '$tableName.$columnName' carries an opaque " +
                        "`DefaultValue.FunctionCall(\"${default.name}\")` default. The model does not " +
                        "capture the function body, so d-migrate cannot prove the call does not " +
                        "reference the renamed object. Resolve the default to a literal, or remove " +
                        "the rename mapping until the slice that introduces explicit default-" +
                        "expression dependencies (Plan-2 §F.4 follow-up).",
                )
            }
        }
        return out
    }
}

/**
 * PostgreSQL is the most permissive dialect: it tracks dependencies
 * via the catalog (OID-based), so FK / Index / PK reprojection after
 * a rename runs natively. Views and trigger bodies are textual and
 * remain opaque in the neutral model, so they only count as
 * AUTOMATIC when [dev.dmigrate.core.model.ViewDefinition.dependencies]
 * is populated by a trustworthy provenance (catalog-derived). T3
 * implements only the conservative-block path on opaque
 * `DefaultValue.FunctionCall` defaults; the broader provenance gate
 * lands with T5's explicit-reprojection slice.
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
        val blockers = RenameDependencyProbes.functionCallDefaultBlockers(
            candidateId = candidate.id,
            tableName = candidate.toName,
            table = desired.tables[candidate.toName],
        )
        return RenameProjection(blockers = blockers)
    }

    override fun classifyColumnRename(
        candidate: RenameColumnCandidate,
        table: TableDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection {
        val blockers = RenameDependencyProbes.functionCallDefaultBlockers(
            candidateId = candidate.id,
            tableName = candidate.tableName,
            table = desired.tables[candidate.tableName],
        )
        return RenameProjection(blockers = blockers)
    }
}

/**
 * MySQL classifies FK + index reprojection as engine-automatic only
 * when the runner probed the live target before `plan(...)`
 * ([RenameCapabilitySource.LIVE_TARGET]). File-to-file and DB-target
 * runs without an explicit probe block when an FK dependency would
 * otherwise need to track a constraint-name conflict.
 *
 * For T3 the MySQL policy mirrors PostgreSQL's opaque-default block;
 * the FK-vs-LIVE-TARGET gate lands once the policy enumerates real FK
 * dependencies (T4 / T5 slice).
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
        val blockers = RenameDependencyProbes.functionCallDefaultBlockers(
            candidateId = candidate.id,
            tableName = candidate.toName,
            table = desired.tables[candidate.toName],
        )
        return RenameProjection(blockers = blockers)
    }

    override fun classifyColumnRename(
        candidate: RenameColumnCandidate,
        table: TableDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection {
        val blockers = RenameDependencyProbes.functionCallDefaultBlockers(
            candidateId = candidate.id,
            tableName = candidate.tableName,
            table = desired.tables[candidate.tableName],
        )
        return RenameProjection(blockers = blockers)
    }
}

/**
 * SQLite rename projection is gated by two PRAGMA-/version-derived
 * capabilities:
 *
 * - `sqliteVersion >= 3.26.0` — earlier versions did not propagate
 *   `RENAME COLUMN` through views or triggers.
 * - `sqliteLegacyAlterTable == false` — when the legacy PRAGMA is
 *   on, the engine omits the same propagation regardless of version.
 *
 * Without both signals (typical for file-to-file or any pre-`plan()`
 * call that did not probe the target), the policy refuses to
 * classify any dependency as automatic and lets the projector fall
 * back to drop+add. The opaque-default block fires uniformly.
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
    ): RenameProjection = collectBlockers(
        candidateId = candidate.id,
        tableName = candidate.toName,
        path = listOf(candidate.toName),
        capabilities = capabilities,
        desired = desired,
    )

    override fun classifyColumnRename(
        candidate: RenameColumnCandidate,
        table: TableDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection = collectBlockers(
        candidateId = candidate.id,
        tableName = candidate.tableName,
        path = listOf(candidate.tableName, candidate.toColumn),
        capabilities = capabilities,
        desired = desired,
    )

    private fun collectBlockers(
        candidateId: String,
        tableName: String,
        path: List<String>,
        capabilities: RenameProjectionCapabilities,
        desired: SchemaDefinition,
    ): RenameProjection {
        val blockers = mutableListOf<RenameProjectionBlocker>()
        if (!engineCapabilitiesPinned(capabilities)) {
            blockers += RenameProjectionBlocker(
                code = RENAME_DEPENDENCY_UNPROJECTABLE,
                candidateId = candidateId,
                path = path,
                message = "SQLite rename-dependency projection requires pinned engine capabilities " +
                    "(sqliteVersion >= 3.26.0 AND sqliteLegacyAlterTable == false, " +
                    "source = LIVE_TARGET or TEST_PINNED). Current capability bundle: " +
                    "source=${capabilities.source}, sqliteVersion=${capabilities.sqliteVersion ?: "<unknown>"}, " +
                    "sqliteLegacyAlterTable=${capabilities.sqliteLegacyAlterTable ?: "<unknown>"}. " +
                    "Fall back to drop+create, or pin the capabilities before plan().",
            )
        }
        blockers += RenameDependencyProbes.functionCallDefaultBlockers(
            candidateId = candidateId,
            tableName = tableName,
            table = desired.tables[tableName],
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
