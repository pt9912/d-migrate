package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.SchemaDefinition

/**
 * Phase H.2: the set of object names the planner must avoid when
 * picking a rebuild temp-table name. Modeled per Plan §6.4 (L975-977)
 * + §9 Phase H.2.
 *
 * **Plan-time-frozen contract**: the snapshot is the planner's input,
 * passed to `SqliteRebuildPlanner.planRebuild` **before** the plan is
 * built. The renderer is pure consumption and must not re-probe; the
 * `newTableTempName` field on the plan is final.
 *
 * The snapshot's origin depends on the plan path:
 *
 * - **Datei-zu-Datei (`schema migrate --plan-only` without
 *   connection)**: synthesised from `current: SchemaDefinition` via
 *   [fromSchema]. The output may miss ad-hoc objects outside the
 *   schema model — the planner's caller is expected to flag this in
 *   the artefact header per §9 Phase H.2.
 * - **Execute (`schema migrate --execute`)**: the CLI fetches
 *   `sqlite_master` and folds the live names into a snapshot before
 *   plan-build. The merge is `fromSchema(current).union(live)`.
 *
 * Both index- and trigger-names are global in SQLite (one namespace
 * per database) so they sit alongside table and view names in the
 * collision check.
 */
internal data class SqliteCatalogSnapshot(
    val tables: Set<String>,
    val views: Set<String>,
    val indices: Set<String>,
    val triggers: Set<String>,
) {
    /**
     * True when [name] is already taken by any object in the snapshot.
     * Used by `SqliteRebuildPlanner.resolveTempTableName` for the
     * `__2`/`__3`/... suffix fallback.
     */
    fun contains(name: String): Boolean =
        name in tables || name in views || name in indices || name in triggers

    /**
     * Union with another snapshot — used by the execute-pipeline to
     * merge the live `sqlite_master` probe result with the schema-
     * synthesised baseline.
     */
    fun union(other: SqliteCatalogSnapshot): SqliteCatalogSnapshot =
        SqliteCatalogSnapshot(
            tables = tables + other.tables,
            views = views + other.views,
            indices = indices + other.indices,
            triggers = triggers + other.triggers,
        )

    companion object {
        val EMPTY = SqliteCatalogSnapshot(emptySet(), emptySet(), emptySet(), emptySet())

        /**
         * Synthesise the snapshot from a [SchemaDefinition] — Datei-zu-
         * Datei plan path. Indices live inside `table.indices`; the
         * anonymous-index fallback name `${table}_${cols}_idx` is
         * computed via the same convention as the renderer so the
         * collision check matches what the renderer would emit.
         */
        fun fromSchema(schema: SchemaDefinition): SqliteCatalogSnapshot {
            val indexNames = mutableSetOf<String>()
            for ((tableName, table) in schema.tables) {
                for (idx in table.indices) {
                    indexNames += idx.name ?: anonIndexName(tableName, idx)
                }
            }
            return SqliteCatalogSnapshot(
                tables = schema.tables.keys.toSet(),
                views = schema.views.keys.toSet(),
                indices = indexNames,
                triggers = schema.triggers.keys.toSet(),
            )
        }

        private fun anonIndexName(
            table: String,
            idx: dev.dmigrate.core.model.IndexDefinition,
        ): String = "${table}_${idx.columns.joinToString("_") { it.name }}_idx"
    }
}
