package dev.dmigrate.driver

/**
 * Live-catalog snapshot read from a SQLite target's `sqlite_master`
 * table at execute time. Pure port-type — the SQLite adapter
 * produces it via its public probe helper, the
 * `SchemaMigrateRunner` carries it through
 * [DdlGenerationOptions.liveSqliteCatalog], and the SQLite renderer
 * unions it with the schema-derived catalog snapshot before the
 * rebuild plan is built.
 *
 * The type is deliberately SQLite-specific. Other dialects that
 * need an analogous live-catalog probe will introduce their own
 * port types; there is no dialect-neutral abstraction here.
 *
 * Plan-2 §A.2.
 */
data class SqliteLiveCatalog(
    val tables: Set<String> = emptySet(),
    val views: Set<String> = emptySet(),
    val indices: Set<String> = emptySet(),
    val triggers: Set<String> = emptySet(),
)

/**
 * Per-render record of how the SQLite-rebuild temp-name collision
 * catalog was assembled. Reported in `SchemaMigrateSummary` so an
 * operator can tell from the report whether the live `sqlite_master`
 * probe ran. Plan-2 §A.2.
 *
 * The "why" of a [SCHEMA_ONLY] mode (file target, non-execute, or a
 * blocked probe) is expressed via [dev.dmigrate.core.diff.migration.DiffDiagnostic]
 * codes — keeping the enum small (two values) and orthogonal to the
 * diagnostic stream.
 */
enum class SqliteCatalogProbeMode {
    /**
     * No live probe; the schema-derived snapshot is the sole input
     * for temp-name collision avoidance. Default for file-to-file,
     * plan-only, and non-SQLite targets. A SQLite + execute path
     * that intends to probe but doesn't (because the dialect is
     * non-SQLite or the target is a file) emits an INFO diagnostic
     * `SQLITE_LIVE_CATALOG_NOT_RUN_FILE_TARGET` alongside this
     * value.
     */
    SCHEMA_ONLY,

    /**
     * The runner read `sqlite_master` on the target before render
     * and merged the result with the schema-derived snapshot. Only
     * set for SQLite + execute paths with a successful probe.
     */
    LIVE_SQLITE_MASTER,
}
