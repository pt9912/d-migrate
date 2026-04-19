package dev.dmigrate.migration

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlResult

/**
 * Generator-near DDL payload with both the raw [DdlResult] and a
 * pre-computed deterministic SQL representation for tool exports.
 *
 * The [deterministicSql] has the runtime timestamp stripped from the
 * DDL header. This normalization happens once in the hexagon, not
 * per tool adapter.
 */
data class MigrationDdlPayload(
    val result: DdlResult,
    val deterministicSql: String,
)

/**
 * Typed rollback state: either not requested or present with payload.
 * Requested rollback without a down-result is not a valid state —
 * it must be caught at bundle construction time.
 */
sealed interface MigrationRollback {
    data object NotRequested : MigrationRollback
    data class Requested(val down: MigrationDdlPayload) : MigrationRollback
}

/**
 * Tool-neutral migration bundle: the central export contract in the hexagon.
 *
 * Encapsulates identity, schema, generator options, DDL payloads, and
 * rollback state. Tool adapters render their artifacts from this bundle
 * without needing access to [DdlGenerator] or the filesystem.
 *
 * Note: the master plan (`implementation-plan-0.7.0.md`) describes the
 * bundle as carrying Up-/Down-"artifacts". This is a deliberate
 * refinement: the bundle carries generator-near *payloads* (DDL result +
 * deterministic SQL). Tool-specific *artifacts* (files with relative
 * paths) are produced by [ToolMigrationExporter] from the bundle.
 *
 * Deliberately excludes source paths, output directories, and report
 * paths — those belong to the application/CLI layer.
 */
data class MigrationBundle(
    val identity: MigrationIdentity,
    val schema: SchemaDefinition,
    val options: DdlGenerationOptions,
    val up: MigrationDdlPayload,
    val rollback: MigrationRollback,
)
