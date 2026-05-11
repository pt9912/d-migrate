package dev.dmigrate.driver

import java.time.Instant

/**
 * LF-003: typed generator options for DDL generation.
 * Lives in hexagon:ports so both application and driver adapters can use it.
 */
data class DdlGenerationOptions(
    val spatialProfile: SpatialProfile = SpatialProfile.NONE,
    /** MySQL-specific: how to handle named sequences. `null` for non-MySQL targets. */
    val mysqlNamedSequenceMode: MysqlNamedSequenceMode? = null,
    /** Stable generation timestamp, typically derived from SOURCE_DATE_EPOCH. */
    val generatedAt: Instant? = null,
    /** Omit volatile provenance fields from generated artifacts. */
    val deterministic: Boolean = false,
    /** Emit foreign keys as deferred ALTER TABLE statements instead of inline CREATE TABLE clauses. */
    val deferForeignKeys: Boolean = false,
    /**
     * Phase H.3b: runner-aware rendering opt-in. When `EXECUTE`, the
     * dialect renderer may emit runner-hook comment markers (today only
     * SQLite-Rebuild uses this: `dmigrate:runner-hook=save-fk-state-...`
     * / `restore-fk-state` instead of the pauschal `PRAGMA foreign_keys
     * = ON;` at the end of the rebuild sequence). The d-migrate runner
     * (`JdbcMigrationExecutor`) parses these markers and reads/restores
     * the prior PRAGMA state. STANDALONE keeps self-contained SQL for
     * external execution.
     *
     * Default `STANDALONE` so SQL artefacts (`schema migrate --plan-only`,
     * `schema rollback` artefact body) remain externally executable.
     * CLI/runner entry points set `EXECUTE` for the live-connection
     * `--execute` path.
     */
    val executionMode: ExecutionMode = ExecutionMode.STANDALONE,
)

/**
 * Phase H.3b: rendering target awareness — STANDALONE for SQL
 * artefacts an external runner consumes, EXECUTE for live d-migrate-
 * runner execution where runner-hook markers are interpreted.
 */
enum class ExecutionMode {
    /** Self-contained SQL artefact for external execution. */
    STANDALONE,

    /** Live d-migrate-runner execution; runner-hook markers active. */
    EXECUTE,
}

/**
 * Spatial profile controlling how geometry columns are mapped to DDL.
 * The profile is resolved from the CLI flag and dialect defaults
 * before any generator is invoked.
 */
enum class SpatialProfile(val cliName: String) {
    POSTGIS("postgis"),
    NATIVE("native"),
    SPATIALITE("spatialite"),
    NONE("none");

    companion object {
        private val BY_CLI_NAME = entries.associateBy { it.cliName }

        fun fromCliName(name: String): SpatialProfile? = BY_CLI_NAME[name.lowercase()]
    }
}

/**
 * MySQL named-sequence emulation strategy (0.9.3).
 * Controls whether `schema generate --target mysql` produces
 * emulated sequence support objects or skips with E056.
 */
enum class MysqlNamedSequenceMode(val cliName: String) {
    /** Skip sequences with action_required E056 (default, backward compatible). */
    ACTION_REQUIRED("action_required"),
    /** Emit dmg_sequences table, dmg_nextval/dmg_setval routines, and BEFORE INSERT triggers. */
    HELPER_TABLE("helper_table");

    companion object {
        private val BY_CLI_NAME = entries.associateBy { it.cliName }

        fun fromCliName(name: String): MysqlNamedSequenceMode? =
            BY_CLI_NAME[name.lowercase(java.util.Locale.ROOT)]
    }
}

/**
 * Central policy for spatial profile defaults and allowed combinations.
 * Single source of truth — CLI, Runner, and tests all use this.
 */
object SpatialProfilePolicy {

    fun defaultFor(dialect: DatabaseDialect): SpatialProfile = when (dialect) {
        DatabaseDialect.POSTGRESQL -> SpatialProfile.POSTGIS
        DatabaseDialect.MYSQL -> SpatialProfile.NATIVE
        DatabaseDialect.SQLITE -> SpatialProfile.NONE
    }

    fun allowedFor(dialect: DatabaseDialect): Set<SpatialProfile> = when (dialect) {
        DatabaseDialect.POSTGRESQL -> setOf(SpatialProfile.POSTGIS, SpatialProfile.NONE)
        DatabaseDialect.MYSQL -> setOf(SpatialProfile.NATIVE, SpatialProfile.NONE)
        DatabaseDialect.SQLITE -> setOf(SpatialProfile.SPATIALITE, SpatialProfile.NONE)
    }

    /**
     * Resolves the effective spatial profile from a raw CLI string and dialect.
     * Returns null if the raw profile name is unknown or not allowed for the dialect.
     */
    fun resolve(dialect: DatabaseDialect, rawProfile: String?): Result {
        if (rawProfile == null) {
            return Result.Resolved(defaultFor(dialect))
        }
        val profile = SpatialProfile.fromCliName(rawProfile)
            ?: return Result.UnknownProfile(rawProfile)
        if (profile !in allowedFor(dialect)) {
            return Result.NotAllowedForDialect(profile, dialect)
        }
        return Result.Resolved(profile)
    }

    sealed interface Result {
        data class Resolved(val profile: SpatialProfile) : Result
        data class UnknownProfile(val raw: String) : Result
        data class NotAllowedForDialect(val profile: SpatialProfile, val dialect: DatabaseDialect) : Result
    }
}
