package dev.dmigrate.driver

import java.time.Instant

/**
 * LF-003: typed generator options for DDL generation.
 * Lives in hexagon:ports so both application and driver adapters can use it.
 *
 * 0.9.7 Phase B.0 refactor: dialekt-spezifische Felder
 * (`mysql*` / `sqlite*` / `routineCapability`) sind in einen sealed
 * [DdlDialectContext] gewandert. `DdlGenerationOptions` traegt nur
 * noch dialekt-neutrale Render-Policies; was MySQL oder SQLite
 * spezifisch ist, lebt in [DdlDialectContext.MySql] /
 * [DdlDialectContext.Sqlite]. Der Default [DdlDialectContext.None]
 * passt fuer file-only / PostgreSQL / Tests ohne dialekt-spezifische
 * Konfiguration.
 *
 * Extension-Felder (`extensionAvailability`, `extensionInstallPolicy`,
 * `extensionInstallPrivilegeStatus`) bleiben am Top-Level — die
 * Typen sind dialekt-neutral und das Konzept koennte in einer
 * spaeteren Tranche auch fuer andere Dialekte aktiv werden.
 */
data class DdlGenerationOptions(
    val spatialProfile: SpatialProfile = SpatialProfile.NONE,
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
     * F.5 Sub-Slice E: declarations for live-data CHECK preflights.
     * Populated by `schema migrate --execute` (after DiffPlanning,
     * before rendering) by running read-only count probes
     * (`SELECT count(*) FROM <table> WHERE NOT (<expression>)`)
     * against the live DB target. The dialect renderers refuse to
     * emit an `AddConstraint(CHECK)` when the matching declaration
     * (matched by [CheckPreflightDeclaration.bindingKey]) reports
     * status FAILED or PROBE_RUNTIME_ERROR.
     *
     * File-only / plan-only / non-CHECK paths leave this empty; the
     * renderer renders natively and the report carries the
     * `NOT_RUN_FILE_TARGET` declaration so the operator knows no
     * live verification happened.
     */
    val checkPreflights: List<CheckPreflightDeclaration> = emptyList(),
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
    /**
     * 0.9.7 Phase B.0: dialekt-spezifischer Render-Kontext. Default
     * [DdlDialectContext.None] passt fuer file-only, PostgreSQL, und
     * Tests ohne dialekt-spezifische Konfiguration. MySQL- und SQLite-
     * Runner setzen [DdlDialectContext.MySql] bzw.
     * [DdlDialectContext.Sqlite] mit den fuer den Lauf aufgeloesten
     * Capabilities und Probes.
     *
     * Die Renderer pruefen den Kontext per Smart-Cast oder Extension-
     * Property ([mysqlContext], [sqliteContext]); ein MySQL-Renderer
     * verlangt ueblicherweise `mysqlContext != null` (gleicher Vertrag
     * wie zuvor die nullable Top-Level-Felder).
     */
    val dialectContext: DdlDialectContext = DdlDialectContext.None,
)

/**
 * 0.9.7 Phase B.0: dialekt-spezifischer Render-Kontext fuer
 * [DdlGenerationOptions]. Ersetzt die zuvor am Top-Level liegenden
 * `mysql*` / `sqlite*` / `routineCapability`-Felder durch einen
 * sealed Typ, der pro Dialekt die fachlich zusammenhaengenden
 * Capabilities und Live-Probe-Daten buendelt.
 *
 * Motivation: generische Port-Typen sollen keine nullable
 * `mysql*` / `sqlite*`-Felder als Sammelschicht tragen. Die sealed
 * Variante macht die Dialekt-Bindung explizit, vermeidet
 * `null`-Lastigkeit in der API, und laesst neue Dialekte (z. B.
 * MariaDB-native sequences) als eigene Branch hinzufuegen, ohne den
 * Top-Level-Typ zu erweitern.
 *
 * Bewusst NICHT hier:
 *
 * - Extension-bezogene Felder bleiben am Top-Level von
 *   [DdlGenerationOptions], weil die Typen
 *   ([ExtensionAvailabilityDeclaration],
 *   [ExtensionInstallPolicy], [ExtensionInstallPrivilegeStatus])
 *   dialekt-neutral sind und das Konzept potenziell ueber PG hinaus
 *   trägt.
 * - [CheckPreflightDeclaration] bleibt ebenfalls am Top-Level —
 *   CHECK-Preflights betreffen alle drei Dialekte.
 *
 * Memory-Pin: `feedback_hexagon_dialect_context.md` (2026-05-27).
 */
sealed interface DdlDialectContext {

    /**
     * Default fuer file-only, PostgreSQL ohne PG-spezifische Config,
     * und Tests, die keine dialekt-spezifischen Capabilities setzen.
     */
    data object None : DdlDialectContext

    /**
     * MySQL/MariaDB-spezifischer Render-Kontext.
     *
     * Aufgeloest durch `SchemaGenerateRunner.resolveMysqlSeqMode`,
     * `SchemaMigrateRenderPipeline.buildRenderOptions` und die
     * MySQL-Sequence-Canonicity-Probe-Stage. Konsumiert von
     * `MysqlDiffSequenceOps`, `MysqlDiffRoutineOps`,
     * `MysqlDiffOtherOps` und der MySQL-DDL-Pipeline.
     *
     * - [namedSequenceMode]: ACTION_REQUIRED (Default fuer
     *   `schema generate --target mysql`) oder HELPER_TABLE
     *   (Emulation aktiv).
     * - [routineCapability]: per-Routine-Kind-Capability, defaults zu
     *   konservativer Oracle-MySQL-Semantik; live MariaDB flippt das
     *   ueber [RoutineCapabilityDefaults.forMysqlServerVersion].
     * - [serverVersion]: live MySQL/MariaDB-Version, `null` fuer
     *   file-zu-Datei.
     * - [sequenceCanonicity]: per-Op Live-DB-Probe-Outcomes gegen
     *   die helper-table-emulation; leer bei file-only.
     */
    data class MySql(
        val namedSequenceMode: MysqlNamedSequenceMode = MysqlNamedSequenceMode.ACTION_REQUIRED,
        val routineCapability: EffectiveRoutineCapability =
            RoutineCapabilityDefaults.forDialect(DatabaseDialect.MYSQL),
        val serverVersion: MysqlServerVersion? = null,
        val sequenceCanonicity: List<MysqlSequenceCanonicityDeclaration> = emptyList(),
    ) : DdlDialectContext

    /**
     * SQLite-spezifischer Render-Kontext.
     *
     * Aufgeloest durch `SchemaGenerateRunner.resolveSqliteSeqMode`
     * (Phase B.1 fuer den `namedSequenceMode`-Slot) und die
     * SQLite-Live-Catalog-Probe-Stage. Konsumiert von
     * `SqliteDiffDdlGenerator`, `SqliteRebuildRenderer` und der
     * SQLite-DDL-Pipeline.
     *
     * - [liveCatalog]: live `sqlite_master`-Snapshot fuer Rebuild-
     *   Temp-Name-Probe; `null` ausserhalb des Execute-Pfades.
     * - [catalogProbeMode]: welcher Input fuer die Rebuild-Temp-Name-
     *   Auswahl gefuettert wurde (`SCHEMA_ONLY` Default,
     *   `LIVE_SQLITE_MASTER` nach erfolgreicher Probe).
     * - [castPreflights]: per-Op CAST-Preflight-Outcomes;
     *   `NOT_RUN_FILE_TARGET` fuer file-only.
     */
    data class Sqlite(
        val namedSequenceMode: SqliteNamedSequenceMode = SqliteNamedSequenceMode.ACTION_REQUIRED,
        val liveCatalog: SqliteLiveCatalog? = null,
        val catalogProbeMode: SqliteCatalogProbeMode = SqliteCatalogProbeMode.SCHEMA_ONLY,
        val castPreflights: List<SqliteCastPreflightDeclaration> = emptyList(),
    ) : DdlDialectContext
}

/** Smart-Cast-freundlicher Accessor: gibt den MySQL-Kontext zurueck oder `null`. */
val DdlGenerationOptions.mysqlContext: DdlDialectContext.MySql?
    get() = dialectContext as? DdlDialectContext.MySql

/** Smart-Cast-freundlicher Accessor: gibt den SQLite-Kontext zurueck oder `null`. */
val DdlGenerationOptions.sqliteContext: DdlDialectContext.Sqlite?
    get() = dialectContext as? DdlDialectContext.Sqlite

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
 * SQLite named-sequence emulation strategy (0.9.7 Phase B.1).
 *
 * Strukturell parallel zu [MysqlNamedSequenceMode]. Eigener Typ statt
 * Wiederverwendung des MySQL-Enums, damit die runner-seitige
 * Dialekt-Validierung sauber bleibt — ein `--mysql-named-sequences`-
 * Wert kann nicht in einen SQLite-Target leaken und umgekehrt.
 *
 * SQLite-Emulation unterscheidet sich von MySQL in zwei Punkten:
 * keine stored functions (die per-INSERT-Logik lebt komplett in
 * einem kanonischen `BEFORE INSERT` + `AFTER INSERT`-Trigger-Paar,
 * validiert gegen SQLite 3.53.1 im §11.1-Prototyp), und die
 * `dmg_sequences`-Zeile traegt eine zusaetzliche `exhausted`-Flag-
 * Spalte, die das Trigger-Paar setzt, wenn `cycle_enabled = 0` und
 * das naechste Inkrement den Bereich verlassen wuerde. Beide
 * Unterschiede sind dialekt-intern — die neutrale
 * `SequenceDefinition` ist die gleiche wie bei MySQL.
 *
 * Modus-Gate:
 *
 * - [ACTION_REQUIRED] (Default fuer SQLite-Targets): heutiges
 *   `E056`-Skip-Verhalten fuer benannte Sequenzen und
 *   `SequenceNextVal`-Spalten-Defaults. Backward-kompatibel.
 * - [HELPER_TABLE]: emittiert die helper-table-Emulation —
 *   `dmg_sequences`-Tabelle, Seed-`INSERT`s, kanonisches
 *   `_bi`/`_ai`-Trigger-Paar pro `SequenceNextVal`-Spalte. Phase
 *   B.3 verdrahtet die eigentliche DDL; das Enum lebt hier, damit
 *   B.1 die Option durch [DdlDialectContext.Sqlite] plumben kann,
 *   bevor der Generator den Pfad oeffnet.
 */
enum class SqliteNamedSequenceMode(val cliName: String) {
    /** Skip sequences with action_required E056 (default, backward compatible). */
    ACTION_REQUIRED("action_required"),
    /**
     * Emit `dmg_sequences` table, seed INSERTs, and a canonical
     * `_bi`/`_ai` trigger pair per `SequenceNextVal` column. Phase
     * B.3 implementation.
     */
    HELPER_TABLE("helper_table");

    companion object {
        private val BY_CLI_NAME = entries.associateBy { it.cliName }

        fun fromCliName(name: String): SqliteNamedSequenceMode? =
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
