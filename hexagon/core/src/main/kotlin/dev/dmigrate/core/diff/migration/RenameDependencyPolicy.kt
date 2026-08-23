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
            RenameProjectionDialect.MSSQL -> MssqlRenameDependencyPolicy
        }
    }
}

/**
 * F.4 T5 view-reprojection helper: produces the explicit `DropView` +
 * `CreateView` pair plus blockers for any view whose
 * `dependencies.tables` declares a table-level dependency on the
 * renamed table.
 *
 * Today's model treats the view `query` as an opaque string —
 * d-migrate cannot rewrite the body to use the new table name.
 * Per Plan-2 §F.4 §3.7 the contract is that the operator supplies
 * the **desired** schema with the view body already pointing at the
 * new name; the projector then emits `DropView(current view)` +
 * `CreateView(desired view)` so PG / MySQL / SQLite re-bind the
 * view body to the post-rename catalog.
 *
 * Blocker case: when the desired schema does not carry the view at
 * all, or its `query` is null, the projector cannot reconstruct the
 * view post-rename. The probe emits a `RENAME_DEPENDENCY_UNPROJECTABLE`
 * blocker; the projector then falls back to drop+create on the table
 * (`RenameProjection.isAutomatic = false`).
 *
 * The returned [absorbedViews] set tells the regular `mapViews`
 * path which `viewsChanged` entries to skip, preventing a duplicate
 * `ReplaceView` alongside the projector's `DropView`/`CreateView`.
 */
internal data class ViewReprojection(
    val operations: List<DiffOperation>,
    val blockers: List<RenameProjectionBlocker>,
    val absorbedViews: Set<String>,
) {
    companion object {
        val EMPTY: ViewReprojection = ViewReprojection(emptyList(), emptyList(), emptySet())
    }
}

internal object RenameViewReprojector {

    fun reprojectViewsForTableRename(
        candidate: RenameTableCandidate,
        current: SchemaDefinition,
        desired: SchemaDefinition,
    ): ViewReprojection {
        if (current.views.isEmpty()) return ViewReprojection.EMPTY
        val ops = mutableListOf<DiffOperation>()
        val blockers = mutableListOf<RenameProjectionBlocker>()
        val absorbed = mutableSetOf<String>()
        for ((viewName, currentView) in current.views) {
            val deps = currentView.dependencies?.tables ?: continue
            // Match `fromName` only. The current schema is pre-rename
            // by definition — a view declaring a dep on `toName` here
            // is either a stale forward reference or catalog noise and
            // would drag unrelated views into reprojection. T6/T7 may
            // revisit this if a legitimate forward-rename case
            // emerges.
            if (candidate.fromName !in deps) continue
            val desiredView = desired.views[viewName]
            if (desiredView == null || desiredView.query.isNullOrBlank()) {
                blockers += RenameProjectionBlocker(
                    code = RENAME_DEPENDENCY_UNPROJECTABLE,
                    candidateId = candidate.id,
                    path = listOf(viewName),
                    message = "View '$viewName' declares a dependency on the renamed table " +
                        "'${candidate.fromName}' -> '${candidate.toName}', but the desired schema does " +
                        "not carry the view${if (desiredView == null) "" else " body (`query` is empty)"}. " +
                        "d-migrate cannot reproject the view without the post-rename body — supply the " +
                        "view in the desired schema with a body referencing the new table name, or " +
                        "remove the rename mapping.",
                )
                continue
            }
            // Plan-2 §8 D.3b Sub-Slice A: a reprojected materialized view
            // must use the dedicated MV op classes, otherwise the
            // emitted `CreateView(materialized=true)` would silently fall
            // back to the D.3a guard instead of the new MV render path.
            // The materialized flag is keyed off the current view (the
            // one being dropped); a flip between current/desired would
            // be a `View↔MaterializedView` conversion which the operator
            // must drop+create explicitly (and which `OperationMapper`
            // surfaces via `BLOCKED_CONVERSION_UNSUPPORTED`). Here both
            // sides are by construction the same view in two states of
            // its underlying-table rebind, so they share `materialized`.
            val materialized = currentView.materialized || desiredView.materialized
            val refType = if (materialized) DiffObjectType.MATERIALIZED_VIEW else DiffObjectType.VIEW
            val dropOpName = if (materialized) "DropMaterializedView" else "DropView"
            val createOpName = if (materialized) "CreateMaterializedView" else "CreateView"
            val dropRef = DiffObjectRef(refType, listOf(viewName))
            val drop: DiffOperation = if (materialized) {
                DiffOperation.DropMaterializedView(
                    id = OperationIdFactory.makeId(dropOpName, dropRef, CanonicalPayload.view(currentView)),
                    objectRef = dropRef,
                    view = currentView,
                    dependencies = setOf(candidate.id),
                )
            } else {
                DiffOperation.DropView(
                    id = OperationIdFactory.makeId(dropOpName, dropRef, CanonicalPayload.view(currentView)),
                    objectRef = dropRef,
                    view = currentView,
                    dependencies = setOf(candidate.id),
                )
            }
            val createRef = DiffObjectRef(refType, listOf(viewName))
            val create: DiffOperation = if (materialized) {
                DiffOperation.CreateMaterializedView(
                    id = OperationIdFactory.makeId(createOpName, createRef, CanonicalPayload.view(desiredView)),
                    objectRef = createRef,
                    view = desiredView,
                    dependencies = setOf(candidate.id, drop.id),
                )
            } else {
                DiffOperation.CreateView(
                    id = OperationIdFactory.makeId(createOpName, createRef, CanonicalPayload.view(desiredView)),
                    objectRef = createRef,
                    view = desiredView,
                    dependencies = setOf(candidate.id, drop.id),
                )
            }
            ops += drop
            ops += create
            absorbed += viewName
        }
        return ViewReprojection(ops, blockers, absorbed)
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
        // T5: views referencing the renamed table need an explicit
        // Drop+Create from the desired body. PostgreSQL's pg_depend
        // would catch identity changes for OID-tracked deps, but the
        // view body is plain SQL text in the neutral model — d-migrate
        // cannot rewrite it, so the safe path is to drop and recreate
        // from the desired schema's body.
        val views = RenameViewReprojector.reprojectViewsForTableRename(candidate, current, desired)
        return RenameProjection(
            explicit = views.operations,
            absorbedViews = views.absorbedViews,
            blockers = views.blockers,
        )
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
        // T3: column-default expressions don't reference table names,
        // so no blocker fires from a FunctionCall probe.
        // T5: views referencing the renamed table need an explicit
        // Drop+Create from the desired body. MySQL never rewrites
        // view bodies through `RENAME TABLE`, so the safest path is
        // to drop and recreate from the desired schema's body.
        // The LIVE_TARGET vs FILE_ONLY gate the matrix describes for
        // FK constraint-name conflicts lands once the policy
        // enumerates FK refs from other tables (T4/T5 follow-up).
        val views = RenameViewReprojector.reprojectViewsForTableRename(candidate, current, desired)
        return RenameProjection(
            explicit = views.operations,
            absorbedViews = views.absorbedViews,
            blockers = views.blockers,
        )
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
 * SQL Server benennt ueber `sp_rename` um — eine Katalogoperation, die die
 * **Identitaet** der Tabelle behaelt. Fremdschluessel und Indizes haengen an
 * der Objekt-ID und folgen deshalb von selbst; was sie NICHT tut, ist
 * abhaengige Definitionen umschreiben:
 *
 * - **Sichten und Routinen** behalten in `sys.sql_modules` ihren alten Text,
 *   also den alten Tabellennamen. SQL Server prueft das beim Umbenennen nicht,
 *   die Sicht bricht erst bei ihrer naechsten Benutzung. Sie brauchen deshalb
 *   dasselbe explizite Drop+Create wie bei MySQL, aus dem Rumpf des
 *   Soll-Schemas.
 * - **Constraint-Namen** driften: ein `df_alterName_spalte` heisst nach dem
 *   Umbenennen weiterhin so. Das ist eine Namens-, keine Korrektheitsfrage —
 *   der Renderer meldet sie als `MSSQL_RENAME_KEEPS_CONSTRAINT_NAMES`, und die
 *   Katalog-Nachschlaege des Diff-Pfads finden die Objekte unabhaengig vom
 *   Namen wieder.
 */
internal object MssqlRenameDependencyPolicy : RenameDependencyPolicy {
    override val dialect: RenameProjectionDialect = RenameProjectionDialect.MSSQL

    override fun classifyTableRename(
        candidate: RenameTableCandidate,
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        capabilities: RenameProjectionCapabilities,
    ): RenameProjection {
        val views = RenameViewReprojector.reprojectViewsForTableRename(candidate, current, desired)
        return RenameProjection(
            explicit = views.operations,
            absorbedViews = views.absorbedViews,
            blockers = views.blockers,
        )
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
