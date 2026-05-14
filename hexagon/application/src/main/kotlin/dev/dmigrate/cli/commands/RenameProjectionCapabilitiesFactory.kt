package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.RenameCapabilitySource
import dev.dmigrate.core.diff.migration.RenameProjectionCapabilities
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.driver.DatabaseDialect

/**
 * Application-side glue between `dev.dmigrate.driver.DatabaseDialect`
 * (driver-port enum) and `RenameProjectionDialect` (core-local
 * discriminator). Keeps `hexagon:core` free of the driver-port
 * dependency.
 */
internal object RenameProjectionCapabilitiesFactory {

    fun dialectFor(dialect: DatabaseDialect): RenameProjectionDialect = when (dialect) {
        DatabaseDialect.POSTGRESQL -> RenameProjectionDialect.POSTGRESQL
        DatabaseDialect.MYSQL -> RenameProjectionDialect.MYSQL
        DatabaseDialect.SQLITE -> RenameProjectionDialect.SQLITE
    }

    /**
     * T1: conservative default for every migrate request. Live capability
     * probes (T-?) will set `source = LIVE_TARGET` plus the relevant
     * version / PRAGMA fields before `DiffPlanner.plan(...)`. Until then,
     * every caller — including `--execute` — runs with `FILE_ONLY` so
     * policies cannot mistakenly assert runtime-dependent
     * `AUTOMATIC_BY_ENGINE` paths.
     */
    fun capabilitiesFor(
        @Suppress("UNUSED_PARAMETER") request: SchemaMigrateRequest,
        @Suppress("UNUSED_PARAMETER") dialect: DatabaseDialect,
    ): RenameProjectionCapabilities = RenameProjectionCapabilities(source = RenameCapabilitySource.FILE_ONLY)
}
