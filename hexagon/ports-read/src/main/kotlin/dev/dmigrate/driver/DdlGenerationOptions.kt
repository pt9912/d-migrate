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
     *
     * **Dialect-Reichweite (heute)**: nur der `SqliteDiffDdlGenerator`
     * konsumiert das Feld; PostgreSQL- und MySQL-Renderer ignorieren es
     * (ihre Streams sind ohnehin runner-owned-tx und brauchen keine
     * Hook-Marker). Wenn `EXECUTE` an einen PG/MySQL-Target ueberreicht
     * wird, ist es ein silent no-op — kein Funktionsbruch, kein Output-
     * Drift.
     */
    val executionMode: ExecutionMode = ExecutionMode.STANDALONE,
    /**
     * Plan-2 §A.2: live `sqlite_master` catalog snapshot for the
     * SQLite-rebuild temp-name collision probe. Set by
     * `SchemaMigrateRunner` when running `--execute` against a SQLite
     * target; null for file targets, non-execute, and non-SQLite
     * dialects. The SQLite renderer unions this with the schema-
     * derived catalog snapshot **before** the rebuild plan is built,
     * so ad-hoc objects in the live DB that aren't in
     * `SchemaDefinition` participate in temp-name resolution.
     *
     * The renderer ignores this field on PG/MySQL targets — they have
     * no rebuild pipeline.
     */
    val liveSqliteCatalog: SqliteLiveCatalog? = null,
    /**
     * Plan-2 §B.2: declarations for SQLite RebuildTable CAST live-data
     * preflights. `schema migrate --execute` populates this after
     * DiffPlanning and before rendering by running read-only count
     * probes against the target SQLite DB. The SQLite renderer refuses
     * to emit a data-copy CAST in EXECUTE mode unless the matching
     * declaration is present with status `PASSED`.
     *
     * File/plan-only paths leave this empty; the renderer reports
     * `NOT_RUN_FILE_TARGET` for the affected operation bindings.
     */
    val sqliteCastPreflights: List<SqliteCastPreflightDeclaration> = emptyList(),
    /**
     * Plan-2 §A.2: which input fed the SQLite-rebuild temp-name
     * collision catalog. `SCHEMA_ONLY` (the default) covers
     * file-to-file, plan-only, non-SQLite, and SQLite-with-execute
     * paths whose probe was suppressed by configuration. The runner
     * sets `LIVE_SQLITE_MASTER` only after a successful `sqlite_master`
     * read; a failed probe blocks before render with Exit 8 and never
     * reaches this field.
     */
    val catalogProbeMode: SqliteCatalogProbeMode = SqliteCatalogProbeMode.SCHEMA_ONLY,
    /**
     * Plan-2 §C.1: explicit target-extension availability declarations
     * for dependency-hardening. Empty means "not verified", especially
     * for file-to-file rendering; dialect renderers must not infer an
     * extension is present from type names alone and must not emit
     * `CREATE EXTENSION` unless a future explicit install policy allows it.
     */
    val extensionAvailability: List<ExtensionAvailabilityDeclaration> = emptyList(),
    /**
     * Plan-2 §C.1: explicit policy for renderer-owned extension installation.
     * The default remains conservative and blocks extension-dependent
     * operations unless availability is verified. `ALLOW_CREATE_IF_MISSING`
     * permits dialect renderers with a native install statement to emit an
     * install prerequisite when availability is MISSING or UNKNOWN.
     */
    val extensionInstallPolicy: ExtensionInstallPolicy = ExtensionInstallPolicy.NEVER,
    /**
     * Plan-2 §C.1: privilege declaration for renderer-owned extension
     * installation. `UNVERIFIED` preserves the first install-policy slice:
     * renderers may plan the install but must keep the side-effect visible.
     * `MISSING` is an explicit pre-render blocker with a distinct diagnostic.
     */
    val extensionInstallPrivilegeStatus: ExtensionInstallPrivilegeStatus =
        ExtensionInstallPrivilegeStatus.UNVERIFIED,
    /**
     * E.1 Routine-Migration Slice C.2/F.11 + 0.9.7
     * routine-capability-configurable-source Sub-Slice A: per-dialect
     * routine capability threaded from
     * `SchemaMigrateRenderPipeline.buildRenderOptions`. Renderers
     * consult this to decide between `CREATE OR REPLACE`,
     * `DROP+CREATE`, and `MANUAL_ACTION_REQUIRED`. PostgreSQL ignores
     * the field today. MySQL consumes it and defaults conservatively to
     * Oracle MySQL semantics (no stored-routine `CREATE OR REPLACE`);
     * live MariaDB targets are enabled by
     * [RoutineCapabilityDefaults.forMysqlServerVersion]. An
     * [EffectiveRoutineCapability.Invalid] value (produced by a future
     * configurable-source parser when operator input is unparsable)
     * routes every routine op to the renderer's
     * `ROUTINE_CAPABILITY_CONFIG_INVALID` block.
     */
    val routineCapability: EffectiveRoutineCapability =
        RoutineCapabilityDefaults.forDialect(DatabaseDialect.MYSQL),
    /**
     * E.1 Routine-Migration Slice C.2: live MySQL/MariaDB server
     * version from the DB target's [SchemaReadResult], or `null` for
     * file-only targets / non-MySQL dialects. Used together with
     * [routineCapability] to decide whether a declared
     * `minServerVersion` floor is satisfied.
     */
    val mysqlServerVersion: MysqlServerVersion? = null,
    /**
     * E.2 Trigger-Migration Sub-Slice A.3: when `true`, operations
     * whose `OperationRisk.hasGap` is set are blocked with
     * `MANUAL_ACTION_REQUIRED` instead of emitted as a multi-statement
     * fallback. The default `false` preserves the lenient pre-A.3
     * behaviour: gap-bearing operations still render (e.g.
     * `ReplaceTrigger` as Drop+Create) and surface the visibility
     * gap via the `W_TRIGGER_REPLACE_GAP` warning diagnostic.
     *
     * Wired from the CLI through `--strict-gap-operations` on
     * `schema migrate`. The `hasGap` flag itself is set by the
     * Mapper from a [TriggerPlanningContext][dev.dmigrate.core.diff.migration.TriggerPlanningContext]
     * — see [TriggerCapability] / [TriggerCapabilityDefaults] for the
     * dialect-level capability source and the
     * `TriggerPlanningContextFactory` application-layer mapper.
     */
    val strictGapOperations: Boolean = false,
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
