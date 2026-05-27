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
 * - **SQLite** — reality-first: today's
 *   `SqliteCapabilityDdlSupport.generateSequences` blocks all
 *   standalone sequences with `E056`, and `SqliteDiffDdlGenerator`
 *   routes sequence ops to `DIALECT_UNSUPPORTED_OPERATION`. Until
 *   the open `sqlite-sequence-emulation-plan.md` implements the
 *   helper-table renderer, every flag is `false`. When that slice
 *   lands it flips the relevant flags as part of its own changes;
 *   Sub-Slice A does not anticipate them.
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
        supportsNamedSequences = false,
        supportsStart = false,
        supportsMinMaxValue = false,
        supportsCycle = false,
        supportsCache = false,
        emitsCachePreallocationWarning = false,
        supportsCurrentValuePreserve = false,
        supportsOwnedBy = false,
    )

    fun forDialect(dialect: DatabaseDialect): SequenceCapability = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgreSQL
        DatabaseDialect.MYSQL -> MySQL
        DatabaseDialect.SQLITE -> SQLite
    }
}
