package dev.dmigrate.driver

/**
 * 0.9.7 Cross-Dialect-Sequencing Sub-Slice A: per-dialect sequence
 * capability. Lives alongside [RoutineCapability] / [TriggerCapability]
 * in `hexagon:ports-read` and follows the same default-only pattern
 * (no operator-supplied configuration source yet).
 *
 * The plan-doc (`docs/planning/done/ImpPlan-0.9.7-cross-dialect-sequencing.md` §5.2)
 * pins the field shape and per-dialect defaults. Renderer-side
 * validation (Sub-Slice B) will read these flags and either emit
 * `SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT`, the existing
 * `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` blocker, or a `W114`
 * cache-preallocation warning.
 *
 * - [supportsNamedSequences]: dialect renderer emits standalone
 *   sequence DDL (PG native, MySQL `dmg_sequences`-helper, SQLite
 *   `dmg_sequences`-helper since 0.9.7 Phase B.3; the SQLite
 *   default-mode action_required still emits `E056` per opt-in
 *   parity with MySQL).
 * - [supportsStart] / [supportsMinMaxValue] / [supportsCycle]: per
 *   `SequenceDefinition`-attribute fidelity in the target renderer.
 *   `false` ⇒ a Cross-Dialect-Transfer with that attribute populated
 *   blocks with `SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT`.
 * - [supportsCache]: whether the dialect carries the `cache` value
 *   into the emitted DDL at all. PG renders `CACHE n`; MySQL's
 *   `dmg_sequences.cache_size` keeps the value as metadata.
 * - [emitsCachePreallocationWarning]: distinguishes "renders the
 *   value" from "actually preallocates at runtime". MySQL stores the
 *   value but does not preallocate, so the renderer emits `W114` by
 *   default — no operator overlay required (plan-doc §4 final).
 * - [supportsCurrentValuePreserve]: backs the
 *   `SequencePreserveStage` `preserveCurrentValue` contract. SQLite
 *   flipped to `true` im 0.9.7-E.3-Folge-Slice — the `SqliteSequenceCurrentValueProbe`
 *   adapter and the SQLite branch in the stage allowlist are wired,
 *   and the renderer's Up/Down for `AlterSequenceCurrentValue` emits
 *   deterministic `UPDATE dmg_sequences SET next_value = …`. The
 *   stage still requires the operator opt-in via
 *   `--sqlite-named-sequences helper_table` and blocks otherwise
 *   with `SEQUENCE_PRESERVE_OPT_IN_REQUIRED`.
 * - [supportsOwnedBy]: PG-only attribute. Today not modeled in
 *   `SequenceDefinition` (PG-reader filters `pg_depend.deptype IN
 *   ('a','i')` out of `schema.sequences`), so the capability is
 *   declarative — Sub-Slice A does not emit
 *   `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT` based on this
 *   flag. The code is reserved for a later neutral-model extension
 *   that introduces an ownership field.
 * - [supportsAtomicPreserve]: dialect can execute Probe + Restore
 *   plus the protected sequence-bearing operations in a single
 *   transaction on one JDBC connection. Atomic-Preserve Phase A
 *   wires the capability field and the
 *   `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` blocker; Phase B/C land
 *   the per-dialect executor implementations that actually flip the
 *   flag to `true`. Until then every dialect stays `false` and the
 *   stage continues with the documented two-transaction fallback.
 * - [supportsAtomicPreserveAllInPlan]: dialect can hold the lock
 *   across **every** preserve candidate in one plan, not just one
 *   sequence at a time. `false` ⇒ the runner is forced to issue a
 *   `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` blocker when a plan
 *   carries more than one preserve candidate. Default `false`
 *   until Phase D lands batch-atomicity.
 * - [transactionalProtectedSequenceOperations]: opaque identifiers
 *   for operation kinds the dialect can execute **inside** the
 *   atomic-runner transaction without triggering an implicit
 *   commit. The empty default conservatively rejects every protected
 *   operation — Phase B/C populate the set with dialect-known-safe
 *   identifiers. The identifier shape is intentionally `String` for
 *   Phase A; Phase B refactors to a `value class
 *   ProtectedOperationId` once the executor wiring exists and the
 *   identifier surface has a real consumer.
 */
data class SequenceCapability(
    val supportsNamedSequences: Boolean,
    val supportsStart: Boolean,
    val supportsMinMaxValue: Boolean,
    val supportsCycle: Boolean,
    val supportsCache: Boolean,
    val emitsCachePreallocationWarning: Boolean,
    val supportsCurrentValuePreserve: Boolean,
    val supportsOwnedBy: Boolean,
    val supportsAtomicPreserve: Boolean = false,
    val supportsAtomicPreserveAllInPlan: Boolean = false,
    val transactionalProtectedSequenceOperations: Set<String> = emptySet(),
)
