package dev.dmigrate.driver

/**
 * Atomic-Preserve Phase B (2026-05-31): opaque identifier for a
 * sequence-bearing operation that the atomic-runner can execute
 * **inside** the Probe + Restore transaction without triggering a
 * dialect-side implicit commit.
 *
 * The identifier is intentionally a thin string wrapper rather than
 * a sealed `DiffOperation`-shaped enum:
 *
 * - The set of "atomic-safe" operations is dialect-dependent (MySQL
 *   commits implicitly on most DDL; PG mostly doesn't; SQLite varies
 *   per statement). The capability matrix
 *   ([SequenceCapability.transactionalProtectedSequenceOperations])
 *   pins per-dialect known-safe identifiers; the runner-side wire-up
 *   (`AtomicSequencePreserveBatch.protectedOperationIds`) carries
 *   the per-plan selection.
 * - The actual operation type lives in `hexagon:core`
 *   ([dev.dmigrate.core.diff.DiffOperation]); the runner-side mapping
 *   from a `DiffOperation` instance to a [ProtectedOperationId] is a
 *   Phase C concern (stage / pipeline refactor) and stays out of the
 *   port layer.
 *
 * The value string is operator-readable and stable enough to surface
 * in the planner-side diagnostic when a dialect declines a protected
 * operation. Example values Phase B will assign:
 *
 * - `"AlterSequenceCurrentValue"` — the restore statement itself
 *   (always safe inside the atomic transaction by construction).
 * - `"InsertRow:dmg_sequences"` — MySQL helper-table maintenance.
 *
 * Plan-Doc: `docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase B (port + batch shape) and §5 Phase E (per-dialect
 * capability defaults).
 */
@JvmInline
value class ProtectedOperationId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "ProtectedOperationId value must not be blank"
        }
    }
}
