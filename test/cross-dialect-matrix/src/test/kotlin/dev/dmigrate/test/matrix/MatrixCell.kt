package dev.dmigrate.test.matrix

import dev.dmigrate.driver.DatabaseDialect

/**
 * A single (workstream × dialect × kind) cell in the cross-dialect
 * regression matrix. Identifies which fixture pair to load and what
 * exit code the file-mode `schema migrate` run must produce.
 *
 * Plan-Doc: `docs/planning/done-archive/quality-coverage-expansion-plan.md`
 * §5.2; `docs/planning/done-archive/diffresult-migration-plan-2.md`
 * §11.2 (the matrix criteria the cells implement).
 */
data class MatrixCell(
    val workstream: String,
    val dialect: DatabaseDialect,
    val kind: Kind,
    val expectedExitCode: Int,
) {
    /**
     * Stable cell identifier used in the test name and in the
     * carve-out registry lookup. Includes the dialect even though
     * fixture YAMLs are shared across dialects (see
     * [fixtureBaseResource]) — the dialect dimension still matters
     * for the runner's expected exit-code semantics.
     */
    val id: String
        get() = "${workstream}/${dialect.fixtureSlug()}/${kind.slug}"

    /**
     * Classpath base for the fixture pair. Dialect-independent: the
     * neutral YAML schema reads the same across PostgreSQL, MySQL
     * and SQLite. Dialect-specific blockers come from the renderer,
     * not the fixture, so a shared current+desired pair is enough
     * to exercise the cross-dialect surface.
     */
    val fixtureBaseResource: String
        get() = "/fixtures/${workstream}/${kind.slug}"

    enum class Kind(val slug: String) {
        /** Happy-path file-mode migrate; expected exit `0`. */
        POSITIVE("positive"),

        /**
         * Planner or renderer surfaces a `MIGRATION_BLOCKED` reason;
         * expected exit `8`. The fixture pair must trigger a
         * concrete planner/renderer blocker, not a validation error.
         */
        BLOCKER("blocker"),

        /**
         * Report-/Exit-Code-Abdeckung per §11.2 of
         * `diffresult-migration-plan-2.md`: same fixture shape as
         * POSITIVE / BLOCKER, but the cell pins a report-structural
         * property (e.g. `primaryBlockedReason`, blocker shape) in
         * addition to the exit code. The current sweep runner already
         * captures the rendered report; future ROLLBACK promotions
         * extend the assertion. Default exit 0; per-workstream
         * blockers override.
         */
        REPORT("report"),

        /**
         * Rollback-Verhalten per §11.2: the cell runs with
         * `generateRollback = true` and pins either the produced
         * rollback artefact's canonical shape OR the
         * `ROLLBACK_NOT_POSSIBLE` / `NOT_REVERSIBLE` blocker for
         * structurally-non-reversible operations. Default exit 0;
         * non-reversible workstreams use exit 8.
         */
        ROLLBACK("rollback"),

        /**
         * Datei-zu-Datei-Verhalten per §11.2 — pins how a workstream
         * that legitimately needs Live-DB knowledge degrades in
         * file-mode. The cell shares POSITIVE / BLOCKER fixtures but
         * asserts that the file-mode-only path produces a sensible
         * non-probe result (e.g. A.2 SQLite catalog probe → file-mode
         * path returns the file-supplied schema unchanged). Default
         * exit 0.
         */
        FILE_MODE("file-mode"),
    }

    companion object {
        val ALL_DIALECTS: List<DatabaseDialect> = listOf(
            DatabaseDialect.POSTGRESQL,
            DatabaseDialect.MYSQL,
            DatabaseDialect.SQLITE,
        )

        val ALL_KINDS: List<Kind> = Kind.values().toList()

        /**
         * Cartesian product of the supplied workstreams × all
         * dialects × all kinds. Cells produced here are *candidates*;
         * the sweep additionally consults the carve-out registry and
         * the fixture catalogue before classifying each as PINNED,
         * CARVE_OUT, or MATRIX_GAP.
         */
        fun candidates(workstreams: Iterable<String>): List<Triple<String, DatabaseDialect, Kind>> =
            workstreams.flatMap { w ->
                ALL_DIALECTS.flatMap { d ->
                    ALL_KINDS.map { k -> Triple(w, d, k) }
                }
            }
    }
}

internal fun DatabaseDialect.fixtureSlug(): String = name.lowercase()
