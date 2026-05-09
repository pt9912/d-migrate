package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk

/**
 * Single rendered SQL statement of a [MigrationDdlResult].
 *
 * One statement may carry multiple [operationIds] (combined
 * `ALTER TABLE` clauses, SQLite rebuild groups), and one fachliche
 * [dev.dmigrate.core.diff.migration.DiffOperation] may produce
 * multiple statements (split-up rebuilds, drop-then-create, etc.).
 *
 * The [risk] reflects the *up*-side risk of executing this specific
 * statement. For Down-renderings, the renderer projects each
 * operation's `OperationRisks.down` into the statement's `risk`
 * field — callers see a uniform "risk for the direction I am about
 * to execute" view.
 */
data class MigrationDdlStatement(
    val sql: String,
    val operationIds: Set<String>,
    val risk: OperationRisk,
    val phase: DiffPhase,
    val notes: List<DiffDiagnostic> = emptyList(),
)
