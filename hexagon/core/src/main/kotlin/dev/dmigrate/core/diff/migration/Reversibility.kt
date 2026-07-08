package dev.dmigrate.core.diff.migration

/**
 * Reversibility classification per [DiffOperation]. Drives whether
 * `schema migrate --generate-rollback` can render an automatic
 * Down-DDL for a given operation.
 *
 * Important contract bits:
 *
 * - [NOT_REVERSIBLE] does **not** block the Up-plan; only the
 *   Down-side rendering. `schema migrate` can still execute the
 *   Up-DDL, but `--generate-rollback` then surfaces a blocker.
 * - [MANUAL_REQUIRED] blocks automatic Down-rendering in the first
 *   slice. Partial rollbacks or operator-supplied Down steps are not
 *   part of 0.9.7.
 * - [AUTOMATIC_WITH_DATA_RISK] means a deterministic inverse exists
 *   but its Down-side may lose data (e.g. `CreateTable` ↔ `DropTable`).
 *   The Down operation carries its own [OperationRisk] independently.
 *
 * See `docs/planning/done-archive/diffresult-migration-plan.md §4.5`.
 */
enum class Reversibility {
    /** Inverse exists and is safe (e.g. AlterColumnDefault). */
    AUTOMATIC,

    /** Inverse exists but its Down-side may lose data. */
    AUTOMATIC_WITH_DATA_RISK,

    /** Inverse needs operator input (e.g. complex AlterColumnType). */
    MANUAL_REQUIRED,

    /** No automatic inverse (e.g. DropColumn, DropTable). */
    NOT_REVERSIBLE,
}
