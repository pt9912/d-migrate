package dev.dmigrate.core.model

data class DependencyInfo(
    val tables: List<String> = emptyList(),
    val views: List<String> = emptyList(),
    val columns: Map<String, List<String>> = emptyMap(),
    val functions: List<String> = emptyList(),
    /**
     * Whether the adapter that produced this dependency projection
     * could read every dependency source it relies on. Defaults to
     * `true` for hand-written schema files and adapters with single-
     * source dependency catalogs (PostgreSQL pg_depend, SQLite
     * sqlite_master deparse).
     *
     * MySQL adapters set this to `false` when `VIEW_TABLE_USAGE` /
     * `VIEW_ROUTINE_USAGE` return 0 rows for an existing view — the
     * INFORMATION_SCHEMA tables only project rows the introspecting
     * user can see, so missing rows can mean either "view has no
     * dependencies" or "user lacks SHOW VIEW on referenced tables".
     * The planner cannot distinguish these cases and must block
     * View-replacing or column-altering operations under the view
     * with a `VIEW_DEPENDENCY_PROJECTION_INCOMPLETE` diagnostic.
     */
    val projectionComplete: Boolean = true,
    val tableProjectionStatus: DependencyProjectionStatus = DependencyProjectionStatus.COMPLETE,
    val columnProjectionStatus: DependencyProjectionStatus = DependencyProjectionStatus.COMPLETE,
    val routineProjectionStatus: DependencyProjectionStatus = DependencyProjectionStatus.COMPLETE,
    val projectionSources: List<String> = emptyList(),
    val projectionErrorClass: String? = null,
) {
    fun dependencyProjectionUsable(): Boolean =
        projectionComplete &&
            tableProjectionStatus.isUsable() &&
            columnProjectionStatus.isUsable() &&
            routineProjectionStatus.isUsable()
}

enum class DependencyProjectionStatus {
    COMPLETE,
    INCOMPLETE_PRIVILEGE,
    INCOMPLETE_OBJECT_MISSING,
    EMPTY_VERIFIED,
    UNKNOWN,
    ;

    fun isUsable(): Boolean = this == COMPLETE || this == EMPTY_VERIFIED
}
