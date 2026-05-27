---
status: accepted
date: 2026-05-27
decision-makers: pt9912
consulted: 0.9.7 cross-dialect-sequencing umbrella plan review
informed: Sub-Slice A / B / C reviewers; future SQLite-sequence-emulation-plan implementer
---

# Cross-Dialect Sequencing — Capability Contract

## Context and Problem Statement

0.9.7 ships sequence migrations as four independent slices:
PostgreSQL native DDL (E.3 first slice, done), MySQL
`dmg_sequences`-helper-table emulation (parallel-plan, done),
SQLite rebuild-based emulation (still
`docs/planning/open/sqlite-sequence-emulation-plan.md`), and
the cross-dialect `preserveCurrentValue` follow-up (parallel-plan,
done).

By the time the umbrella plan-doc (`docs/planning/done/ImpPlan-0.9.7-cross-dialect-sequencing.md`,
closing in Sub-Slice E) reached scope, three of the four slices had
already merged. The plan-doc therefore changed shape from "upstream
architecture for parallel slices" to "retrofit harmonisation of
already-shipped slices". Three drifts surfaced between plan-doc
intent and code reality:

1. **preserve-not-supported routing**: the plan-doc wanted
   `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` to map to
   `MANUAL_ACTION_REQUIRED`; `PlannerBlockerClassifier` mapped it to
   `DIALECT_UNSUPPORTED_OPERATION` with the explicit reason "SQLite
   has no sequence emulation yet."
2. **MySQL `cache` mapping**: the plan-doc wanted a default-blocker
   + overlay-gated `W114` warning; the renderer emitted `W114`
   directly without an overlay.
3. **PG `OWNED BY`**: the plan-doc wanted a renderer-side blocker
   (`SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT`); the
   PG reverse-reader filtered owned sequences via
   `pg_depend.deptype IN ('a','i')`, so the renderer never saw them.

Without a single decision-record, the next sequence-related slice
(SQLite-helper-table, MariaDB-native, neutral-model ownership field)
would re-litigate these same questions and risk diverging.

## Decision Drivers

- Honour the as-shipped contract where the code's reasoning is
  load-bearing; don't reverse merged behaviour just to match a
  draft plan-doc.
- Reality-first SQLite defaults so the capability layer reflects
  what the renderer actually does today, not what it might do once
  the open SQLite plan lands.
- Forward-compatibility for the OWNED BY case and the SQLite
  per-attribute case — reserve codes so a later slice can emit them
  without rewriting the classifier.
- Minimum API surface: no operator-supplied configuration source
  for sequences in 0.9.7. The capability layer is defaults-only.
- Mirror the existing `RoutineCapability` / `TriggerCapability`
  patterns so reviewers don't have to learn a third shape.

## Considered Options

For each drift, the umbrella-plan review weighed (code wins,
plan-doc wins, sunset compromise). The chosen path picked the
already-shipped contract for the two drifts where the code's
reasoning was sounder than the draft, and adapted the plan-doc for
the third where reader-side filtering kept ownership out of the
neutral model entirely.

## Decision Outcome

Five decisions, all pinned in the umbrella plan-doc §3.1 and
materialised in Sub-Slices A (`SequenceCapability` defaults), B
(MySQL diff-path `W114` via capability + classifier constants), and
C (`spec/neutral-model-spec.md §9.2`, `spec/cli-spec.md §4.7`).

### D1 — Sequence identity across dialects

`NamedSequence.name` (the neutral-model field, equivalent to
`SequenceDiff.name`) is the single source of truth. Dialect
emulations (`MySQL.dmg_sequences.name`, the planned
SQLite-helper-table `dmg_sequences.name`) MUST map to this name
without transformation.

### D2 — Cross-dialect transfer contract

Renderers consult `SequenceCapability` and emit
`SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT` when a populated
attribute hits an unsupported flag at the target. The OP-level
case (`supportsNamedSequences = false`, today SQLite) keeps
`DIALECT_UNSUPPORTED_OPERATION` because no operator action
enables a missing dialect concept; the attribute-level code is
reserved for partial-support cases (e.g., a future SQLite renderer
that supports named sequences but not `cycle`). MySQL `cache` is
not a blocker: the renderer emits `W114` by default — both in the
full-schema and diff path — because the `helper_table` already
persists the value as metadata. No operator overlay is required.

### D3 — Capability matrix as versioned spec

The cross-dialect capability matrix lives in
`spec/neutral-model-spec.md §9.2`, and the catalog of string-coded
sequence blocker codes (with their `MigrationBlockedReason`
routing) lives in `spec/cli-spec.md §4.7`. Both are normative;
the in-code `SequenceCapabilityDefaults` must stay consistent with
them.

### D4 — Sequence-default-reprojection (Cross-Dialect)

The F.4 Sub-Slice D `SequenceDefaultReprojector` already operates
dialect-neutrally for `RenameSequence`. For cross-dialect transfer
it applies implicitly: the neutral-model diff carries no rename
information (source and target share the sequence name), so the
reprojector simply sees the canonical name on both sides. Operators
who want an explicit cross-dialect rename use the existing F.4
overlay schema with `objectType = "sequence"`. No new code is
required.

### D5 — Capability-source-resolution pattern

`SequenceCapability` mirrors `RoutineCapability` /
`TriggerCapability`: a data class in `hexagon:ports-read` with
per-dialect defaults via `SequenceCapabilityDefaults.forDialect()`.
0.9.7 ships defaults-only; an `EffectiveSequenceCapability` sealed
envelope analogous to `EffectiveRoutineCapability` will land only
when a later tranche introduces CLI / YAML overrides for sequences.

### Consequences

- Good, because the four already-shipped slices now have one
  binding contract instead of three independent assumptions.
- Good, because the SQLite reality-first defaults make the
  capability layer's claims true today; the open SQLite plan
  flips the relevant flags as part of its own changes rather than
  inheriting unfounded `true`s.
- Good, because the two forward-compat classifier codes
  (`SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT`,
  `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT`) let later
  slices emit them without re-touching the classifier or routing
  table.
- Bad, because the OP- vs. attribute-level split for SQLite means
  the same observable behaviour (SQLite refuses sequence ops) is
  signalled by two different reasons depending on whether
  per-attribute emulation lands. Operators reading raw exit codes
  will see `DIALECT_UNSUPPORTED_OPERATION` today but may see
  `MANUAL_ACTION_REQUIRED` once partial SQLite emulation ships.
  The cli-spec §4.7 catalog calls this out.
- Neutral, because if MariaDB-native `CREATE SEQUENCE` (10.3+)
  lands as its own capability gate, the existing
  `SequenceCapabilityDefaults.forDialect(MYSQL)` will need a
  vendor-version branch — analogous to
  `RoutineCapabilityDefaults.forMysqlServerVersion` — without
  changing this ADR.

### Confirmation

- `hexagon:ports-read:SequenceCapabilityTest` pins the per-dialect
  defaults (Sub-Slice A).
- `MysqlDiffSequenceOpsCacheWarningTest` (Sub-Slice B.1) pins
  `W114` emission in CreateSequence UP, AlterSequence (both
  directions when `cache` differs), and DropSequence DOWN; pins
  silence on CreateSequence DOWN and DropSequence UP.
- `PlannerBlockerClassifierTest` (Sub-Slice B.0) pins
  `MANUAL_ACTION_REQUIRED` routing for the two forward-compat
  codes.
- `spec/neutral-model-spec.md §9.2` and `spec/cli-spec.md §4.7`
  carry the normative contract.

## More Information

- Umbrella plan-doc:
  `docs/planning/done/ImpPlan-0.9.7-cross-dialect-sequencing.md`
  (closing in Sub-Slice E).
- Parallel slices that this ADR retroactively harmonises:
  `docs/planning/done/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`,
  `docs/planning/done/ImpPlan-0.9.7-sequence-preserve-current-value.md`,
  `docs/planning/done/mysql-sequence-emulation-plan.md`.
- Open follow-up the ADR explicitly defers to:
  `docs/planning/open/sqlite-sequence-emulation-plan.md`.
- Related capability patterns:
  `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/RoutineCapability.kt`,
  `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/TriggerCapability.kt`.
- Related ADRs: ADR-0001 / ADR-0002 picked WARNING for
  routine-side risks; this ADR picks the same dial-down stance
  for the MySQL `cache` lossy mapping (default `W114`, no overlay
  gate).
