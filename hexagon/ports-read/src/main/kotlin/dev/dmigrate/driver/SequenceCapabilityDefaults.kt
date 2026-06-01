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

    // Atomic-Preserve Phase A (2026-05-31): every dialect declares the
    // three new capability fields explicitly even though the defaults
    // all evaluate to `false` / `emptySet()` today. Forcing the
    // declaration makes the per-dialect matrix legible at a glance and
    // pins the contract that Phases B/C will flip; an implicit
    // data-class-default would let a future dialect-renderer commit
    // appear to support the atomic path without anyone updating the
    // capability table.
    private val PostgreSQL = SequenceCapability(
        supportsNamedSequences = true,
        supportsStart = true,
        supportsMinMaxValue = true,
        supportsCycle = true,
        supportsCache = true,
        emitsCachePreallocationWarning = false,
        supportsCurrentValuePreserve = true,
        supportsOwnedBy = true,
        // Atomic-Preserve Phase A/B: PG's actual lock strategy is
        // `pg_advisory_xact_lock(hashtext(...))` plus `SET LOCAL
        // lock_timeout` — the original plan-doc proposal `LOCK TABLE
        // <seq> IN ACCESS EXCLUSIVE MODE` proved un-implementable in
        // Phase B.2 (PG refuses the statement against sequence
        // relations and `nextval` is by-design lock-free). See
        // `sequence-preserve-atomic-lock-plan.md` §4.1 Korrektur.
        // The executor (`PostgresAtomicSequencePreserveExecutor`)
        // exists since Phase B.2 but is not yet wired into the
        // SchemaMigrate pipeline — Phase C.4 will flip this flag to
        // `true` and populate `transactionalProtectedSequenceOperations`.
        supportsAtomicPreserve = false,
        supportsAtomicPreserveAllInPlan = false,
        transactionalProtectedSequenceOperations = emptySet(),
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
        // Atomic-Preserve Phase A/B: MySQL's lock strategy
        // (`SELECT … FOR UPDATE` on `dmg_sequences` +
        // `SET SESSION innodb_lock_wait_timeout`) is documented in
        // `sequence-preserve-atomic-lock-plan.md` §4.2. Several DDL
        // statements (`ALTER TABLE`, `CREATE INDEX`) issue implicit
        // commits on MySQL and therefore cannot live inside the
        // atomic transaction — the empty
        // `transactionalProtectedSequenceOperations` set is a positive
        // allowlist that Phase C.4 wiring fills with the IDs the
        // `MysqlAtomicSequencePreserveExecutor` declares as
        // implicit-commit-safe.
        supportsAtomicPreserve = false,
        supportsAtomicPreserveAllInPlan = false,
        transactionalProtectedSequenceOperations = emptySet(),
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
        // Atomic-Preserve Phase A/B: SQLite's `BEGIN IMMEDIATE` plus
        // `PRAGMA busy_timeout` strategy is documented in
        // `sequence-preserve-atomic-lock-plan.md` §4.3. RESERVED-lock
        // semantics mean SQLite blocks every concurrent writer for
        // the entire transaction window — the
        // `SqliteAtomicSequencePreserveExecutor` (Phase B.4) keeps
        // the protected-operation surface tight; Phase C.4 wiring
        // declares which `ProtectedOperationId`s are safe inside the
        // RESERVED window. `false` until C.4 lands.
        supportsAtomicPreserve = false,
        supportsAtomicPreserveAllInPlan = false,
        transactionalProtectedSequenceOperations = emptySet(),
    )

    fun forDialect(dialect: DatabaseDialect): SequenceCapability = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgreSQL
        DatabaseDialect.MYSQL -> MySQL
        DatabaseDialect.SQLITE -> SQLite
    }
}
