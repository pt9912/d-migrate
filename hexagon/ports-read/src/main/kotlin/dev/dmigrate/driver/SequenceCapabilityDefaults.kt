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
 *   default). `supportsCurrentValuePreserve` flipped to `true` im
 *   0.9.7-E.3-Folge-Slice: the `SqliteSequenceCurrentValueProbe`
 *   adapter plus the deterministic Up/Down rendering in
 *   `SqliteDiffSequenceOps` mean the preserve flow runs end-to-end
 *   when the operator opts into
 *   `--sqlite-named-sequences helper_table`; without the opt-in the
 *   `SequencePreserveStage` blocks with
 *   `SEQUENCE_PRESERVE_OPT_IN_REQUIRED` BEFORE the probe opens.
 *   `supportsOwnedBy` stays `false` because SQLite has no ownership
 *   concept.
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
        // 0.9.7 SQLite preserve-current-value E.3-Folge-Slice: the renderer
        // (`SqliteDiffSequenceOps.renderAlterSequenceCurrentValue`),
        // the probe (`SqliteSequenceCurrentValueProbe`), and the
        // SequencePreserveStage allowlist now form a complete loop.
        // Activation is gated by `--sqlite-named-sequences helper_table`;
        // the stage emits SEQUENCE_PRESERVE_OPT_IN_REQUIRED when the
        // operator hasn't opted in. The capability flag describes what
        // the dialect's renderer can express, not which CLI mode is
        // currently selected.
        supportsCurrentValuePreserve = true,
        supportsOwnedBy = false,
    )

    fun forDialect(dialect: DatabaseDialect): SequenceCapability = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgreSQL
        DatabaseDialect.MYSQL -> MySQL
        DatabaseDialect.SQLITE -> SQLite
    }
}
