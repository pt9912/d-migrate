package dev.dmigrate.driver.migration

/**
 * Renderer-supplied, *a-priori* dialect-execution hints for a single
 * [MigrationDdlStatement] (Plan-2 §A.1). Independent of the runner's
 * post-execute observations: these fields describe what the dialect
 * *could* do when this statement runs, not what *did* happen.
 *
 * The runner's plan-level
 * [MigrationDdlResult.sideEffectsPossible] remains the canonical
 * post-execute observation; the per-statement
 * [sideEffectsPossible] flag here is the renderer's pre-execute
 * estimate per dialect contract.
 *
 * Default values are deliberately conservative: a non-renderer
 * construction site (test fixtures, the rollback-artefact splitter)
 * that omits the hints reports [TransactionBehavior.UNKNOWN] /
 * [LockBehavior.UNKNOWN] / all boolean flags false. The report
 * treats UNKNOWN as "no claim", never as "transactional".
 */
data class DialectExecutionHints(
    val transactionBehavior: TransactionBehavior = TransactionBehavior.UNKNOWN,
    val lockBehavior: LockBehavior = LockBehavior.UNKNOWN,
    /**
     * True if the dialect implicitly commits surrounding work when
     * the statement starts. MySQL DDL ⇒ true; PostgreSQL DDL ⇒ false.
     * Mirrors [TransactionBehavior.IMPLICIT_COMMIT] but exists as a
     * separate flag so future dialects can pair an `IMPLICIT_COMMIT`
     * statement with a renderer that has not yet decided whether
     * preceding work is also affected.
     */
    val implicitCommitPossible: Boolean = false,
    /**
     * True if a mid-statement failure could leave durable state
     * behind despite the runner's transaction handling. Set by the
     * renderer based on dialect contract (MySQL DDL ⇒ true). The
     * runner aggregates this into
     * [MigrationDdlResult.sideEffectsPossible] when post-execute
     * observation cannot prove the stronger negative.
     */
    val sideEffectsPossible: Boolean = false,
    /**
     * True if the statement requires exclusive access to its target
     * objects (no concurrent readers/writers). Default conservative
     * for `ALTER TABLE` / `DROP TABLE` paths across all dialects.
     * Optional per Plan-2 §A.1: a renderer that cannot establish the
     * claim leaves it false.
     */
    val requiresExclusiveAccess: Boolean = false,
) {
    companion object {
        /**
         * Conservative default for non-renderer construction sites
         * (test fixtures, rollback artefact splitter, parser stubs).
         * Production renderers MUST supply concrete hints, not
         * fall back on this.
         */
        val UNKNOWN: DialectExecutionHints = DialectExecutionHints()
    }
}
