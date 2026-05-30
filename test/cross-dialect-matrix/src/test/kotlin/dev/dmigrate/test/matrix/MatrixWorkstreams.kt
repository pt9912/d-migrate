package dev.dmigrate.test.matrix

/**
 * Canonical list of the 22 workstreams from
 * `docs/planning/in-progress/diffresult-migration-plan-2.md` §4-§10
 * that the cross-dialect matrix must cover by B-Vervollständigung
 * (per the Sub-Slice B Akzeptanzkriterium in
 * `quality-coverage-expansion-plan.md` §7).
 *
 * Workstream IDs match the plan-doc sub-section identifiers (e.g.
 * `G.1`, `F.5`, `D.3`) so cross-referencing stays trivial.
 *
 * Sub-Slice B (this commit) pins five workstreams ([PINNED]); the
 * remaining 17 are registered as carve-outs in
 * `fixtures/carve-outs.yaml` and surface through [CarveOutRegistry].
 * Sub-Slice B-Vervollständigung promotes the carve-outs to pinned
 * cells or accepts them as permanent carve-outs with documented
 * justification.
 */
internal object MatrixWorkstreams {

    /**
     * Pinned workstreams across Sub-Slice B and B-Vervollständigung.
     * Each has at least one fixture pair under
     * `fixtures/<workstream>/<kind>/`; dialect-specific deviations
     * are handled via per-cell carve-outs (permanent or provisional).
     *
     * Selection criteria: workstreams whose file-mode behaviour is
     * stable enough that a pinning today is unlikely to flap, and
     * whose blocker path has a documented cross-dialect reason.
     */
    val PINNED: List<String> = listOf(
        "G.1",  // transactionScope: positive create-table across PG/MySQL/SQLite
        "G.2",  // Rollback artefact: positive with --generate-rollback emission
        "G.3",  // Execution-status report: positive emits status surface for stable report shape
        "A.1",  // Locking/transactional-DDL hints: positive surfaces dialect-specific hints in the report
        "F.5",  // CHECK/EXCLUDE diff: positive add CHECK (PG/SQLite); MySQL needs server-version (carved)
        "D.3",  // Materialized Views: positive PG, BLOCKER MySQL/SQLite (MV not supported by dialect)
        "E.2",  // Trigger migration: positive add trigger (MySQL); PG/SQLite need per-dialect bodies (carved)
    )

    /**
     * The full plan-doc workstream catalogue (22 entries). Order
     * matches the plan-doc table of contents.
     */
    val ALL: List<String> = listOf(
        "G.1", "G.2", "G.3",
        "A.1", "A.2",
        "B.1", "B.2",
        "C.1", "C.2",
        "D.1", "D.2", "D.3",
        "E.1", "E.2", "E.3",
        "F.0", "F.1", "F.2", "F.3", "F.4", "F.5",
        // 0.9.7-internal slice tracked alongside the plan-doc but
        // outside the §4-§10 catalogue. Included so the carve-out
        // registry stays exhaustive.
        "F.4-renderer-blocker-bridge",
    )

    /** Workstreams that are NOT pinned in this slice — must be covered by carve-outs. */
    val UNPINNED: List<String> = ALL - PINNED.toSet()
}
