package dev.dmigrate.driver

/**
 * 0.9.7 Cross-Dialect-Sequencing Sub-Slice A: per-dialect default
 * [SequenceCapability]. Lowest-precedence layer — there is no
 * operator-supplied configuration source for sequences in 0.9.7;
 * an `Effective…`-envelope analog to [EffectiveRoutineCapability]
 * will land only if a later tranche adds CLI / YAML overrides.
 *
 * Defaults reflect today's renderer reality (plan-doc §5.2 final):
 *
 * - **PostgreSQL** — full support; `cache` renders as `CACHE n`
 *   with PG's real runtime preallocation, so no `W114` warning.
 *   `OWNED BY` is renderable at the SQL level but PG's
 *   reverse-reader filters owned sequences out of the standalone
 *   `schema.sequences` slot today, so the flag stays declarative
 *   (`true` is honest about PG's capability; emission only kicks in
 *   once the neutral model carries an ownership field).
 * - **MySQL (Oracle / MariaDB)** — `dmg_sequences`-helper-table
 *   emulation. All attributes are persisted in the helper row;
 *   `cache_size` is metadata only (no runtime preallocation), so
 *   `emitsCachePreallocationWarning = true` ⇒ renderer emits `W114`
 *   by default. `OWNED BY` is not abbildbar — `false`.
 * - **SQLite** — reality-first: 0.9.7 Phase B.3 landed the
 *   `helper_table`-mode `dmg_sequences` emulation
 *   (`SqliteSequenceDdlSupport`), so the full-schema renderer
 *   carries named sequences plus all per-attribute fidelity flags.
 *   `cache_size` is metadata-only (SQLite is single-writer / no
 *   runtime preallocation) ⇒ `emitsCachePreallocationWarning = true`
 *   so the renderer emits `W114` analog to MySQL. The default-mode
 *   action_required path still emits `E056` skips — the capability
 *   describes what the renderer is **capable of**, not which mode
 *   is currently selected (parallel to MySQL's opt-in helper_table
 *   default). `supportsCurrentValuePreserve` stays `false` even
 *   though 0.9.7 F2 landed the `SqliteDiffSequenceOps` renderer
 *   for `AlterSequenceCurrentValue` — `SequencePreserveStage`
 *   still has a hard MySQL/PostgreSQL allowlist and SQLite has no
 *   `SequenceCurrentValueProbe` implementation. Flipping requires
 *   both (separate follow-up workstream). `supportsOwnedBy` stays
 *   `false` because SQLite has no ownership concept.
 */
object SequenceCapabilityDefaults {

    private val PostgreSQL = SequenceCapability(
        supportsNamedSequences = true,
        supportsStart = true,
        supportsMinMaxValue = true,
        supportsCycle = true,
        supportsCache = true,
        emitsCachePreallocationWarning = false,
        supportsCurrentValuePreserve = true,
        supportsOwnedBy = true,
    )

    private val MySQL = SequenceCapability(
        supportsNamedSequences = true,
        supportsStart = true,
        supportsMinMaxValue = true,
        supportsCycle = true,
        supportsCache = true,
        emitsCachePreallocationWarning = true,
        supportsCurrentValuePreserve = true,
        supportsOwnedBy = false,
    )

    private val SQLite = SequenceCapability(
        supportsNamedSequences = true,
        supportsStart = true,
        supportsMinMaxValue = true,
        supportsCycle = true,
        supportsCache = true,
        emitsCachePreallocationWarning = true,
        // 0.9.7 G7 consumer-check: SqliteDiffSequenceOps.renderAlter-
        // SequenceCurrentValue is wired but unreachable today —
        // `SequencePreserveStage` blocks the dialect at line 105
        // (allowlist MySQL/PostgreSQL only) and SQLite has no
        // `SequenceCurrentValueProbe` implementation. The renderer
        // stays as forward-compat code; the flag stays `false` so
        // the planner does not emit `AlterSequenceCurrentValue` ops
        // that would surface as `NOT_SUPPORTED_BY_DIALECT` blockers.
        supportsCurrentValuePreserve = false,
        supportsOwnedBy = false,
    )

    fun forDialect(dialect: DatabaseDialect): SequenceCapability = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgreSQL
        DatabaseDialect.MYSQL -> MySQL
        DatabaseDialect.SQLITE -> SQLite
    }
}
