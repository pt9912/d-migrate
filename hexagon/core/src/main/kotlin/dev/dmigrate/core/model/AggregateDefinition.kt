package dev.dmigrate.core.model

/**
 * A user-defined aggregate (N7). Two dialect forms are represented, kept
 * apart by which fields are populated; [sourceDialect] records the origin.
 *
 * **PostgreSQL — SQL-defined** (`SFUNC`/`STYPE`/`FINALFUNC` over functions):
 * ```
 * CREATE AGGREGATE group_concat(text) (
 *     SFUNC = group_concat_transfn, STYPE = internal,
 *     FINALFUNC = group_concat_finalfn, INITCOND = ''
 * );
 * ```
 * Set [stateType] + [transitionFunction] (+ optional final/init/sort).
 *
 * **MySQL — loadable native UDF** (compiled C, loaded from a shared library):
 * ```
 * CREATE AGGREGATE FUNCTION group_concat RETURNS STRING SONAME 'udf_agg.so';
 * ```
 * Set [returnType] + [library].
 *
 * The two forms cannot be mechanically translated into each other (SQL
 * state-transition vs. compiled `.so`); a generator emits only the form
 * that matches its dialect and skips the other with a manual-action note.
 */
data class AggregateDefinition(
    /** Aggregate input argument types, e.g. `["text"]`. Empty means `(*)`. */
    val inputTypes: List<String> = emptyList(),
    // ── PostgreSQL SQL-defined form ──
    /** `STYPE` — the internal state data type (PostgreSQL form). */
    val stateType: String? = null,
    /** `SFUNC` — the state transition function name (PostgreSQL form). */
    val transitionFunction: String? = null,
    /** `FINALFUNC` — optional final-calculation function name. */
    val finalFunction: String? = null,
    /** `INITCOND` — optional initial state condition (literal). */
    val initialCondition: String? = null,
    /** `SORTOP` — optional associated sort operator (for `MIN`/`MAX`-style). */
    val sortOperator: String? = null,
    // ── MySQL loadable-UDF form ──
    /** `RETURNS` type of the loadable UDF (MySQL form), e.g. `STRING`. */
    val returnType: String? = null,
    /** `SONAME` shared library holding the UDF implementation (MySQL form). */
    val library: String? = null,
    /** Origin dialect; distinguishes which form the aggregate carries. */
    val sourceDialect: String? = null,
) {
    /** True when this carries the PostgreSQL SQL-defined form. */
    val isSqlDefined: Boolean get() = stateType != null && transitionFunction != null

    /** True when this carries the MySQL loadable-UDF form. */
    val isLoadableUdf: Boolean get() = library != null
}
