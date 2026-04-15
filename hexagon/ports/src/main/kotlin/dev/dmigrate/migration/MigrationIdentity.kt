package dev.dmigrate.migration

import dev.dmigrate.driver.DatabaseDialect

/**
 * Fully resolved, deterministic identity for a migration export.
 *
 * Contains everything needed to derive file names, migration IDs,
 * and version stamps — independent of any specific tool adapter.
 */
data class MigrationIdentity(
    val tool: MigrationTool,
    val dialect: DatabaseDialect,
    val version: String,
    val versionSource: MigrationVersionSource,
    val slug: String,
)
