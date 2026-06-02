package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions

/**
 * Renders a [DiffResult] (Soll-vs-Ist plan) into dialect-specific
 * SQL via [MigrationDdlResult]. This is the migration-pipeline
 * counterpart to the existing full-state [dev.dmigrate.driver.DdlGenerator]
 * (Plan §6.1) — the two coexist:
 *
 * - `DdlGenerator` renders a complete schema from scratch (used by
 *   `schema generate`).
 * - `DiffDdlGenerator` renders the delta needed to migrate from
 *   `current` to `desired` (used by `schema migrate` / `rollback`).
 *
 * Implementations must:
 *
 * - never re-order operations — the planner has already topo-sorted
 *   them; the renderer may aggregate or split, but the relative order
 *   of the operations that produced each statement is preserved;
 * - never emit SQL for operations the planner blocked — those go
 *   into `operationsSkipped` with a corresponding diagnostic;
 * - surface dialect-unsupported operations via a
 *   `DIALECT_UNSUPPORTED_OPERATION` blocker, not a thrown exception.
 *
 * Implementations are stateless and thread-safe. The [dialect] field
 * lets the runner sanity-check that the chosen renderer matches the
 * connection's dialect before generating.
 */
interface DiffDdlGenerator {
    val dialect: DatabaseDialect

    fun generateUp(
        diff: DiffResult,
        options: DdlGenerationOptions = DdlGenerationOptions(),
    ): MigrationDdlResult

    fun generateDown(
        diff: DiffResult,
        options: DdlGenerationOptions = DdlGenerationOptions(),
    ): MigrationDdlResult
}
