package dev.dmigrate.driver.mysql

/**
 * E.3 MySQL Sequence-Diff Sub-Slice B: per-render-pass tracker that
 * guarantees the helper-table bootstrap (`dmg_sequences` CREATE TABLE
 * + `dmg_nextval` / `dmg_setval` CREATE FUNCTION) is emitted at most
 * once per migration direction.
 *
 * Lifecycle: one instance per [MysqlDiffRenderContext]. UP and DOWN
 * directions each get their own context, so a Down-rebuild that
 * re-creates sequences (the inverse of `DropSequence`) can emit the
 * bootstrap once without colliding with the Up direction's own
 * bootstrap.
 *
 * The tracker is intentionally direction-agnostic: callers decide
 * whether the operation they are about to render needs a row-level
 * `INSERT INTO dmg_sequences` (UP CreateSequence, DOWN DropSequence)
 * and consult [needsBootstrap] before emitting; pure
 * `UPDATE dmg_sequences` (AlterSequence) or `DELETE FROM dmg_sequences`
 * (UP DropSequence, DOWN CreateSequence) paths skip the bootstrap.
 *
 * Plan-Doc: `docs/planning/done-archive/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`
 * §3.1 + §6 Sub-Slice B.
 */
internal class MysqlSequenceMigrationContext {

    private var bootstrapEmitted: Boolean = false

    /**
     * Returns `true` until [markBootstrapEmitted] flips the latch.
     * Callers use this to decide whether to render the
     * `CREATE TABLE dmg_sequences` + `CREATE FUNCTION dmg_nextval` /
     * `dmg_setval` triple before the first row-level INSERT.
     */
    fun needsBootstrap(): Boolean = !bootstrapEmitted

    /**
     * Latches the bootstrap state so subsequent calls to
     * [needsBootstrap] return `false`. Called once after the
     * bootstrap SQL has been emitted for this direction.
     */
    fun markBootstrapEmitted() {
        bootstrapEmitted = true
    }
}
