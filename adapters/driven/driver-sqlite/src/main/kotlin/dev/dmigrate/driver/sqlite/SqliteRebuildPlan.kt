package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.ViewDefinition

/**
 * Adapter-internal `DialectMigrationPlan` instance for SQLite per
 * Plan §6.4 (L884-936). Produced by `SqliteRebuildPlanner.planRebuild`
 * (Phase H.1b) and consumed by `SqliteRebuildRenderer.render` (H.1b);
 * the canonical RebuildTable sequence is derived deterministically
 * from the fields here.
 *
 * Phase H.1a introduces the struct only — Planner and Renderer still
 * use the pre-H.1 `(table, bucket, source, target)` signature. H.1b
 * wires the data flow; H.2 fills temp-name collision resolution; H.3
 * fills the [dependentViewsToDrop] / [dependentViewsToRecreate] +
 * trigger pairs (analog to view drop/recreate but for the trigger-
 * set, both sourced from the per-side schema snapshot); H.4 fills
 * [preflight].
 *
 * Direction-agnostic: the dispatcher passes the appropriate
 * `(oldTable, newTable)` pair — Up uses `(current, desired)`, Down
 * uses `(desired, current)`. [sourceOperationIds] and [risk] are
 * the two `DialectMigrationStep` contract fields from §6.4
 * (L880-915) so a runner failure can attribute back to the
 * absorbed business ops and the migrate-report can aggregate
 * BlockSet/risk correctly.
 */
internal data class SqliteRebuildPlan(
    /** The original (and final) table name after the RENAME step. */
    val originalTableName: String,

    /** Schema definition of the table to copy data FROM. */
    val oldTable: TableDefinition,

    /** Schema definition of the table to copy data INTO (target shape). */
    val newTable: TableDefinition,

    /** Temporary table name used between CREATE-temp and RENAME-to-original. */
    val newTableTempName: String,

    /**
     * The full bucket of operations this rebuild absorbs — required
     * for context-side book-keeping (`ctx.markRendered(op)` keys on
     * `op` itself, not just its id). The planner attaches the bucket
     * here from `SqliteRebuildPlanner.classify`'s output.
     */
    val bucketOperations: List<dev.dmigrate.core.diff.migration.DiffOperation>,

    /**
     * Union of all op-ids the rebuild absorbs — equals
     * [bucketOperations].map { it.id }.toSet() by construction.
     * Stored explicitly because every statement the renderer emits is
     * tagged with this set, and pre-computing it once avoids walking
     * the bucket on each emit. §6.4 L880-915
     * `DialectMigrationStep.sourceOperationIds`.
     */
    val sourceOperationIds: Set<String>,

    /**
     * Pre-aggregated bucket risk in the rendering direction. The
     * planner takes the direction-aware risk from the bucket's ops
     * and reduces it to a single `OperationRisk` here so the renderer
     * doesn't need to walk operations at emit time. §6.4 L880-915
     * `DialectMigrationStep.risk`.
     */
    val risk: OperationRisk,

    /** Column mapping (preserved / added / dropped) plus blockers. */
    val mapping: SqliteColumnMappingModel,

    /** Indices to recreate on the rebuilt table — taken from
     *  [newTable].indices. */
    val indexesToRecreate: List<IndexDefinition>,

    /**
     * Views that reference the rebuilt table in `current` and must be
     * dropped before `DROP TABLE`. Empty in H.1a — populated by H.3.
     */
    val dependentViewsToDrop: List<NamedViewDefinition> = emptyList(),

    /**
     * Views that reference the rebuilt table in `desired` and must be
     * created after the RENAME step. The two view sets are independent
     * (see H.3 in §9): a view dropped within the same plan exists in
     * the drop-list but not in the recreate-list, and vice versa for
     * newly-added views. Empty in H.1a — populated by H.3.
     */
    val dependentViewsToRecreate: List<NamedViewDefinition> = emptyList(),

    /**
     * Triggers that attach to the rebuilt table in `current`. Dropped
     * implicitly by `DROP TABLE` but kept in the plan for diagnostic
     * attribution; the renderer emits an explicit `DROP TRIGGER`
     * before `DROP TABLE` to make the operation auditable. Empty in
     * H.1a — populated by H.3.
     */
    val dependentTriggersToDrop: List<NamedTriggerDefinition> = emptyList(),

    /**
     * Triggers that attach to the rebuilt table in `desired`. Recreated
     * after the RENAME. Empty in H.1a — populated by H.3.
     */
    val dependentTriggersToRecreate: List<NamedTriggerDefinition> = emptyList(),

    /**
     * Preflight checks per §6.4 L928-934. Empty in H.1a — populated
     * by H.4. The pre-H.4 NOT_NULL_BACKFILL_REQUIRED /
     * SQLITE_CAST_NOT_WHITELISTED diagnostics in [mapping] cover the
     * `ADDED_COLUMNS_FILLABLE` kind statically; the remaining five
     * (`TABLE_EXISTS`, `TEMP_NAME_AVAILABLE`, `SOURCE_COLUMNS_EXIST`,
     * `DEPENDENCIES_KNOWN`, `FOREIGN_KEYS_CHECKABLE`) land in H.4.
     */
    val preflight: List<SqliteRebuildPreflightCheck> = emptyList(),
)

/**
 * Carrier for `(name, ViewDefinition)`. The view-name is the
 * schema-canonical key in `SchemaDefinition.views`; the planner emits
 * it explicitly here so the renderer doesn't need a back-reference
 * to the schema map.
 */
internal data class NamedViewDefinition(val name: String, val definition: ViewDefinition)

/**
 * Carrier for `(triggerKey, TriggerDefinition)`. `TriggerDefinition.table`
 * is part of the schema; the trigger-key is the codec-canonical
 * `<table>.<name>` form so the renderer can emit deterministic
 * `DROP TRIGGER`/`CREATE TRIGGER` SQL.
 */
internal data class NamedTriggerDefinition(val name: String, val definition: TriggerDefinition)

/**
 * Column-mapping decomposition per Plan §6.4 (L917-926). Fields are
 * disjoint and exhaustively partition the union of source and target
 * columns:
 *
 * - [preservedColumns]: in both old and new tables. Each entry carries
 *   `sourceColumn`, `targetColumn` and `expressionSql` as **separate
 *   structured fields**, not just an opaque SELECT-expression string.
 *   H.4 `SOURCE_COLUMNS_EXIST` reads `sourceColumn` directly to
 *   verify the mapping's source-side references without parsing SQL.
 * - [addedColumns]: only in the new table. Filled via DEFAULT literal
 *   or NULL.
 * - [droppedColumnNames]: only in the old table. Not selected.
 * - [notNullBackfillBlocked]: a new NOT NULL column with neither
 *   DEFAULT nor source data — emits a BLOCKER.
 * - [castNotWhitelisted]: a type change that the cast matrix refuses
 *   — emits a BLOCKER.
 *
 * [isBlocked] short-circuits the renderer before any SQL emission.
 * [orderedInsertEntries] is the single source of truth for the
 * column iteration order across the `CREATE TABLE <temp>` and the
 * `INSERT INTO <temp>(cols...) SELECT exprs... FROM <orig>`.
 */
internal data class SqliteColumnMappingModel(
    val preservedColumns: List<ColumnCopyMapping>,
    val addedColumns: List<AddedColumnFill>,
    val droppedColumnNames: List<String>,
    val notNullBackfillBlocked: List<String>,
    val castNotWhitelisted: List<CastBlockEntry>,
) {
    val isBlocked: Boolean
        get() = notNullBackfillBlocked.isNotEmpty() || castNotWhitelisted.isNotEmpty()

    val orderedInsertEntries: List<ColumnInsertEntry>
        get() {
            val out = mutableListOf<ColumnInsertEntry>()
            for (p in preservedColumns) {
                out += ColumnInsertEntry(targetColumn = p.targetColumn, expressionSql = p.expressionSql)
            }
            for (a in addedColumns) {
                out += ColumnInsertEntry(targetColumn = a.targetColumn, expressionSql = a.expressionSql)
            }
            return out
        }
}

/**
 * Single preserved-column mapping entry per §6.4 L917-921. Structured
 * `(sourceColumn, targetColumn, expressionSql)` triple — H.4 reads
 * `sourceColumn` directly for SOURCE_COLUMNS_EXIST without SQL parsing.
 *
 * `expressionSql` is `"<quoted-source>"` when [typeChanged] is false
 * and `CAST(<quoted-source> AS <newType>)` when true. The renderer
 * uses [expressionSql] verbatim in the INSERT-SELECT.
 */
internal data class ColumnCopyMapping(
    val sourceColumn: String,
    val targetColumn: String,
    val expressionSql: String,
    val typeChanged: Boolean,
)

/**
 * Fill expression for a column that exists only in the target table.
 * Structured `(targetColumn, expressionSql)` pair. `expressionSql` is
 * either a DEFAULT-literal, `NULL`, or a sentinel comment that only
 * appears when [SqliteColumnMappingModel.isBlocked] is true (renderer
 * never emits the sentinel — the blocker short-circuits first).
 */
internal data class AddedColumnFill(
    val targetColumn: String,
    val expressionSql: String,
)

internal data class CastBlockEntry(
    val column: String,
    val source: NeutralType,
    val target: NeutralType,
)

/**
 * Carrier for the per-column entry in the canonical
 * `INSERT INTO <temp>(cols...) SELECT exprs... FROM <orig>`. Both
 * preserved and added columns contribute to this single list; the
 * renderer iterates [SqliteColumnMappingModel.orderedInsertEntries].
 */
internal data class ColumnInsertEntry(
    val targetColumn: String,
    val expressionSql: String,
)

/**
 * Preflight check entries per Plan §6.4 (L928-934 Typentwurf,
 * L985-990 Ablauf). Six discrete kinds with per-kind execution form
 * (plan-time static vs. runner-side execute-time) per the H.4
 * specification in §9 Phase H.
 *
 * Populated by Phase H.4; H.1a keeps this carrier shape so the other
 * H-slices have a stable contract to populate.
 */
internal data class SqliteRebuildPreflightCheck(
    val kind: SqliteRebuildPreflightKind,
    /** Optional descriptor — e.g. which column/view/trigger triggered the check. */
    val target: String? = null,
    /** Human-readable message for diagnostics or runner logs. */
    val message: String,
)

internal enum class SqliteRebuildPreflightKind {
    /** Expected table exists in `current` snapshot. Plan-time static. */
    TABLE_EXISTS,

    /** Temp-table name does not collide with the live catalog. Plan-time static. */
    TEMP_NAME_AVAILABLE,

    /** All preserved-column sources exist in `current`. Plan-time static. */
    SOURCE_COLUMNS_EXIST,

    /** Dependency projection is complete (no unknown views/triggers). Plan-time static. */
    DEPENDENCIES_KNOWN,

    /** NOT NULL / DEFAULT rules satisfied for added columns. Plan-time static. */
    ADDED_COLUMNS_FILLABLE,

    /** FK-constraint integrity probe is executable. Runner-side execute-time. */
    FOREIGN_KEYS_CHECKABLE,
}
