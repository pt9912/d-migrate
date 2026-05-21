package dev.dmigrate.core.diff.migration

/**
 * 0.9.7 preserve-current-value Sub-Slice A: dialect-aware reference to
 * a named sequence object. Carries enough information for a
 * `SequenceCurrentValueProbe` adapter (PG, MySQL) to resolve the
 * dialect-specific lookup key — `SELECT last_value FROM <schema>.<name>`
 * for PG, `SELECT next_value FROM dmg_sequences WHERE name = <key>`
 * for MySQL — without having to re-derive dialect routing at the port
 * boundary.
 *
 * Lives in `hexagon:core` because [DiffOperation.AlterSequenceCurrentValue]
 * carries it as a structural field. `hexagon:core` cannot depend on
 * `hexagon:ports-common`'s `DatabaseDialect`, so we reuse the
 * established [RenameProjectionDialect] core-local discriminator —
 * the same boundary pattern F.4 introduced and `TriggerPlanningContext`
 * already documents. The application layer maps its `DatabaseDialect`
 * to this enum at the `DiffPlanner.plan(...)` call site, identical to
 * the rename / trigger paths.
 *
 * @property name canonical sequence identifier (without quoting). The
 *           probe / renderer adds dialect-specific quoting at use site.
 * @property schema optional namespace / schema name. PG uses this to
 *           qualify `SELECT last_value FROM <schema>.<name>`; MySQL
 *           and SQLite (helper-table emulation) ignore it. `null` means
 *           "search-path default" / unqualified.
 * @property dialect drives the dialect-specific resolution at probe and
 *           renderer time. Probe + renderer for one
 *           [DiffOperation.AlterSequenceCurrentValue] MUST see the same
 *           dialect — the planner stamps it at op-emit time.
 */
data class SequenceObjectRef(
    val name: String,
    val schema: String? = null,
    val dialect: RenameProjectionDialect,
)
